/**
 * One in-memory Pi session: a single Agent instance holding the temporary
 * multi-turn transcript. Nothing is persisted; when the worker process exits
 * the conversation context is gone. At most one active run per session.
 */
import { Agent } from '@earendil-works/pi-agent-core';
import { Type } from 'typebox';
import { buildModelEndpoint } from './model.js';
import { logDiagnostic, MAX_RUN_TEXT_CHARS, MAX_TOOL_RESULT_CHARS } from './protocol.js';

const TOOL_DESCRIPTIONS = {
  list_tables:
    'List tables in one known database/schema. Discover missing namespaces with list_namespaces first. Omit target fields to use the conversation default target; ' +
    'explicit connection/catalog/schema select another validated target. The result includes the ' +
    'resolved target identity and a truncated flag; when truncated is true you have NOT seen all tables.',
  describe_table:
    'Read column metadata (type, comment, primary-key flag) for one table. Use the full identity of an ' +
    'explicit reference; only unspecified target fields fall back to the conversation default target.',
  query_database:
    'Execute one supported read-only SELECT/WITH through the Java host. Writes, DDL, batches, locks, ' +
    'sequences, and unknown functions are forbidden. Use the complete target identity from the turn ' +
    'or reference. Limited to 15 seconds and 200 rows. Check success, execution states and truncated; ' +
    'a partial result cannot establish a full-dataset conclusion. Never retry a write through this tool.',
};

const targetFields = {
  connectionId: Type.Optional(
    Type.Number({ description: 'Connection ID from list_connections, the turn context or an explicit reference.' }),
  ),
  catalogName: Type.Optional(Type.String({ description: 'Catalog/database name, when applicable.' })),
  schemaName: Type.Optional(Type.String({ description: 'Schema name, when applicable.' })),
};

export class PiSession {
  /**
   * @param {string} sessionId
   * @param {(message: object) => void} send protocol sender
   */
  constructor(sessionId, send) {
    this.sessionId = sessionId;
    this.send = send;
    this.agent = null;
    this.apiKey = null;
    this.activeRun = null; // { runId, textChars, pendingTools: Map, cancelRequested, toolCalls, finished }
  }

  get busy() {
    return this.activeRun !== null;
  }

  /**
   * Start one run. Emits exactly one `finished` message for this run.
   * @param {object} msg validated start message
   */
  async start(msg) {
    const runId = msg.runId;
    if (this.activeRun) {
      this.send({ type: 'finished', runId, status: 'FAILED', errorCode: 'PI_SESSION_BUSY' });
      return;
    }
    let endpoint;
    try {
      endpoint = buildModelEndpoint(msg.model);
    } catch (error) {
      this.send({
        type: 'finished',
        runId,
        status: 'FAILED',
        errorCode: error.code ?? 'PI_WORKER_ERROR',
      });
      return;
    }
    const run = {
      runId,
      textChars: 0,
      pendingTools: new Map(),
      cancelRequested: false,
      toolCalls: 0,
      modelCalls: 0,
      inputTokens: 0,
      outputTokens: 0,
      finished: false,
    };
    this.activeRun = run;
    run.modelRequests = 0;
    run.toolRequests = 0;
    run.maxModelCalls = msg.limits?.maxModelCalls ?? 16;
    run.maxToolCalls = msg.limits?.maxToolCalls ?? 32;
    run.role = msg.role ?? 'assistant';
    run.toolProfile = msg.toolProfile ?? 'general';
    this.endpoint = endpoint;
    this.apiKey = msg.apiKey;
    try {
      if (!this.agent) {
        this.agent = new Agent({
          initialState: {
            systemPrompt: msg.systemPrompt,
            model: endpoint.model,
            tools: this.#buildTools(run.role, run.toolProfile),
          },
          streamFn: (model, context, options) => {
            const active = this.activeRun;
            if (!active || active.budgetExceeded || ++active.modelRequests > active.maxModelCalls) {
              if (active) active.budgetExceeded = true;
              throw new Error('AGENT_BUDGET_EXCEEDED');
            }
            return this.endpoint.streams.streamSimple(model, context, options);
          },
          getApiKey: () => this.apiKey,
          prepareNextTurnWithContext: ({ context }) => {
            const active = this.activeRun;
            if (!active?.schemaReadComplete && !active?.schemaDelegationComplete) return undefined;
            // A schema-only workflow ends exploration after its verified batch.
            // Declare the empty loadout in the transcript before the summary call.
            return { context: { ...context, tools: [] } };
          },
          sessionId: this.sessionId,
          identity: { attribution: { name: 'data-agent-local-pi' } },
        });
        this.agent.subscribe((event) => this.#onEvent(event));
      } else {
        this.agent.state.model = endpoint.model;
        this.agent.state.tools = this.#buildTools(run.role, run.toolProfile);
      }
      await this.agent.prompt(msg.message);
      this.#finish(run, 'SUCCEEDED', null);
    } catch (error) {
      // prompt() only settles after agent_end; reaching catch means an
      // unexpected worker-side failure outside the normal loop.
      logDiagnostic('run failed outside agent loop:', error);
      this.#finish(run, run.cancelRequested ? 'CANCELLED' : 'FAILED', run.cancelRequested ? null : 'PI_WORKER_ERROR');
    }
  }

  cancel(runId) {
    const run = this.activeRun;
    if (!run || run.runId !== runId) return;
    run.cancelRequested = true;
    for (const pending of run.pendingTools.values()) {
      pending.reject(new Error('cancelled'));
    }
    run.pendingTools.clear();
    this.agent?.abort();
  }

  resolveTool(runId, toolCallId, isError, result) {
    const run = this.activeRun;
    if (!run || run.runId !== runId) return false; // late result: drop
    const pending = run.pendingTools.get(toolCallId);
    if (!pending) return false;
    run.pendingTools.delete(toolCallId);
    const text = typeof result === 'string' ? result : String(result ?? '');
    if (isError) {
      pending.reject(new Error(text.slice(0, 2000) || 'tool failed'));
    } else {
      try {
        const body = JSON.parse(text);
        if (run.toolProfile === 'schema' && pending.name === 'describe_tables' && body.tables?.length > 0) {
          run.schemaReadComplete = true;
        }
        if (pending.schemaDelegation && body.taskResults?.length > 0 &&
          body.taskResults.every(task => task.status === 'SUCCESS' && task.structures?.length > 0) && body.truncated !== true) {
          run.schemaDelegationComplete = true;
        }
      } catch { /* Non-JSON tool text cannot advance the verified schema workflow. */ }
      const capped =
        text.length > MAX_TOOL_RESULT_CHARS ? `${text.slice(0, MAX_TOOL_RESULT_CHARS)}…[truncated]` : text;
      pending.resolve({ content: [{ type: 'text', text: capped }] });
    }
    return true;
  }

  dispose() {
    if (this.activeRun) this.cancel(this.activeRun.runId);
  }

  #buildTools(role, profile = 'general') {
    const execute = (name) => (toolCallId, params, signal) =>
      new Promise((resolve, reject) => {
        const run = this.activeRun;
        if (!run) {
          reject(new Error('no active run'));
          return;
        }
        if (++run.toolRequests > run.maxToolCalls) {
          run.budgetExceeded = true;
          reject(new Error('AGENT_BUDGET_EXCEEDED'));
          return;
        }
        if (signal) {
          if (signal.aborted) {
            reject(new Error('cancelled'));
            return;
          }
          signal.addEventListener('abort', () => reject(new Error('cancelled')), { once: true });
        }
        run.pendingTools.set(toolCallId, { resolve, reject, name,
          schemaDelegation: name === 'callingExplorerSubAgent' && params?.tasks?.length > 0 &&
            params.tasks.every(task => task.mode === 'schema') });
        this.send({
          type: 'tool_call',
          runId: run.runId,
          toolCallId,
          name,
          arguments: JSON.stringify(params ?? {}),
        });
      });
    const tools = [
      {
        name: 'list_tables',
        label: 'List tables',
        description: TOOL_DESCRIPTIONS.list_tables,
        parameters: Type.Object({ ...targetFields }),
        execute: execute('list_tables'),
      },
      {
        name: 'describe_table',
        label: 'Describe table',
        description: TOOL_DESCRIPTIONS.describe_table,
        parameters: Type.Object({
          tableName: Type.String({ description: 'Unqualified table name.', minLength: 1, maxLength: 128 }),
          ...targetFields,
        }),
        execute: execute('describe_table'),
      },
      {
        name: 'describe_tables',
        label: 'Describe related tables',
        description: 'Read compact columns, primary keys and actual JDBC foreign keys for 1-4 named tables in one fixed scope. ' +
          'Prefer this for relationships and ER diagrams instead of repeated describe_table or catalog SQL. ' +
          'Check foreignKeys.available and truncated; unavailable keys do not mean no foreign keys exist.',
        parameters: Type.Object({
          tableNames: Type.Array(Type.String({ minLength: 1, maxLength: 128 }), { minItems: 1, maxItems: 4 }),
          ...targetFields,
        }),
        execute: execute('describe_tables'),
      },
      {
        name: 'query_database',
        label: 'Read-only query',
        description: TOOL_DESCRIPTIONS.query_database,
        parameters: Type.Object({
          query: Type.String({ description: 'One read-only SELECT/WITH statement.', minLength: 1, maxLength: 65536 }),
          ...targetFields,
        }),
        execute: execute('query_database'),
      },
    ];
    if (role !== 'explorer') tools.push({
      name: 'list_connections',
      label: 'List data sources',
      description: 'List available data sources with connectionId, name and dbType. No target is required and no database is opened. ' +
        'When no connection is specified, discover sources first. Use a unique or user-named source; ask by name if ambiguous.',
      parameters: Type.Object({}),
      execute: execute('list_connections'),
    }, {
      name: 'list_namespaces',
      label: 'List databases and schemas',
      description: 'Discover namespaces on one connection. With no catalog, lists catalogs if supported; otherwise lists schemas. ' +
        'Supply a chosen catalog to discover its schemas. Check supportsCatalog, supportsSchema and truncated. Do not guess names.',
      parameters: Type.Object({ ...targetFields }),
      execute: execute('list_namespaces'),
    }, {
      name: 'callingExplorerSubAgent',
      label: 'Explorer',
      description: 'Delegate up to two focused database discovery tasks to independent Pi Explorer agents. ' +
        'Each task has a fixed connection and optional catalog/schema. Returns taskResults with status, summaryText and verified objects. ' +
        'Required for multi-table schema analysis, relationships, business-module exploration and ER diagrams once the target is known. ' +
        'Normally send one focused task with timeoutSeconds=45, select at most four core tables first, ' +
        'pass existing discoveries and database type in context, and let Explorer read metadata rather than doing it yourself first. ' +
        'Do not delegate writes or broad scans of every connection.',
      parameters: Type.Object({ tasks: Type.Array(Type.Object({
        connectionId: Type.Number({ minimum: 1 }),
        catalogName: Type.Optional(Type.String()), schemaName: Type.Optional(Type.String()),
        instruction: Type.String({ minLength: 1, maxLength: 4000 }),
        mode: Type.Optional(Type.Union([Type.Literal('schema'), Type.Literal('general')], {
          description: 'schema: metadata/relationships/ER only; batch once, then summarize without more tools. general: tasks needing data queries or mixed work.',
        })),
        context: Type.Optional(Type.String({ maxLength: 4000 })),
        timeoutSeconds: Type.Optional(Type.Integer({ minimum: 1, maximum: 45 })),
      }), { minItems: 1, maxItems: 2 }) }),
      execute: execute('callingExplorerSubAgent'),
    });
    return role === 'explorer' && profile === 'schema'
      ? tools.filter(tool => ['list_tables', 'describe_tables'].includes(tool.name)) : tools;
  }

  #onEvent(event) {
    const run = this.activeRun;
    if (!run || run.finished) return;
    switch (event.type) {
      case 'message_update': {
        const delta = event.assistantMessageEvent;
        if (delta?.type === 'text_delta' && typeof delta.delta === 'string' && delta.delta) {
          run.textChars += delta.delta.length;
          if (run.textChars > MAX_RUN_TEXT_CHARS) {
            logDiagnostic('run text budget exceeded, aborting');
            this.cancel(run.runId);
            this.#finish(run, 'FAILED', 'AGENT_BUDGET_EXCEEDED');
            return;
          }
          this.send({ type: 'text_delta', runId: run.runId, delta: delta.delta });
        }
        break;
      }
      case 'tool_execution_end':
        run.toolCalls += 1;
        break;
      case 'turn_end': {
        const message = event.message;
        if (message?.role === 'assistant') {
          run.modelCalls += 1;
          const usage = message.usage;
          if (usage && Number.isFinite(usage.input) && Number.isFinite(usage.output)) {
            run.inputTokens += usage.input;
            run.outputTokens += usage.output;
          }
        }
        break;
      }
      case 'agent_end':
        this.#onAgentEnd(run);
        break;
      default:
        break;
    }
  }

  #onAgentEnd(run) {
    const messages = this.agent?.state.messages ?? [];
    const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
    if (run.cancelRequested || lastAssistant?.stopReason === 'aborted') {
      this.#finish(run, 'CANCELLED', null);
      return;
    }
    if (lastAssistant?.stopReason === 'error') {
      // Details (endpoint, provider message) stay in stderr; never on stdout.
      logDiagnostic('model call failed:', lastAssistant.errorMessage ?? '<no message>');
      this.#finish(run, 'FAILED', 'PI_MODEL_ERROR');
      return;
    }
    this.#finish(run, 'SUCCEEDED', null);
  }

  #finish(run, status, errorCode) {
    if (!this.activeRun || this.activeRun.runId !== run.runId || run.finished) return;
    if (run.budgetExceeded && status !== 'CANCELLED') { status = 'FAILED'; errorCode = 'AGENT_BUDGET_EXCEEDED'; }
    run.finished = true;
    for (const pending of run.pendingTools.values()) pending.reject(new Error('run finished'));
    run.pendingTools.clear();
    this.activeRun = null;
    const message = { type: 'finished', runId: run.runId, status };
    if (errorCode) message.errorCode = errorCode;
    message.usage = {
      modelCalls: run.modelCalls,
      toolCalls: run.toolCalls,
      inputTokens: run.inputTokens,
      outputTokens: run.outputTokens,
      usageComplete: run.modelCalls > 0,
    };
    this.send(message);
  }
}
