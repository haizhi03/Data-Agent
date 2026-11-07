#!/usr/bin/env node
/**
 * T2 durable prototype worker: same stdio JSON-lines host protocol as the v1
 * worker, but conversations, runs and tool waits live in pi-durable storage
 * and survive a process kill.
 *
 * Config (env):
 *   DURABLE_STORAGE     'sqlite' (default) | 'memory'
 *   DURABLE_SQLITE_PATH database file (default ./durable-worker.sqlite);
 *                       the event journal lives at <path>.journal.jsonl
 *
 * See protocol.js for the wire format. Diagnostics go to stderr only.
 */
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context';
import {
  ConversationBusy,
  createRegistry,
  defineExtension,
  Harness,
  MemoryStorage,
} from '@earendil-works/pi-durable';
import { openNodeSqliteStorage } from '@earendil-works/pi-durable/storage/sqlite/node';
import { PI_AI_VERSION } from '@earendil-works/pi-ai';
import { Journal } from './journal.js';
import { ModelRegistry } from './models.js';
import { buildTools, toolsForRole } from './tools.js';
import {
  PROTOCOL_VERSION,
  MAX_INBOUND_LINE_BYTES,
  createStdoutSender,
  logDiagnostic,
  readJsonLines,
} from './protocol.js';

const ctx = BACKGROUND_CONTEXT;
const send = createStdoutSender();

const STORAGE_KIND = process.env.DURABLE_STORAGE ?? 'sqlite';
const SQLITE_PATH = process.env.DURABLE_SQLITE_PATH ?? './durable-worker.sqlite';
// Upper bound on how long a recovered run's model retry waits for the host to
// re-register the credential (re-send start) before failing no_model.
const MODEL_GATE_MS = Number.isInteger(Number(process.env.DURABLE_MODEL_GATE_MS))
  ? Number(process.env.DURABLE_MODEL_GATE_MS)
  : 120_000;
// How long SQLite waits for a competing file lock (e.g. a second worker
// booting on the same database) before the open fails.
const SQLITE_BUSY_TIMEOUT_MS = Number.isInteger(Number(process.env.DURABLE_SQLITE_BUSY_TIMEOUT_MS))
  ? Number(process.env.DURABLE_SQLITE_BUSY_TIMEOUT_MS)
  : 5_000;

const journal = new Journal(STORAGE_KIND === 'memory' ? null : `${SQLITE_PATH}.journal.jsonl`);
const modelRegistry = new ModelRegistry({ gateMs: MODEL_GATE_MS });

/** @type {Map<string, object>} sessionId -> session state */
const sessions = new Map();
/** @type {Map<string, string>} runId -> sessionId (live and journaled) */
const runsById = new Map();
/** @type {Map<string|number, object>} conversationId -> session state */
const sessionsByConversation = new Map();

class SessionState {
  constructor(sessionId, conversationId, conversation) {
    this.sessionId = sessionId;
    this.conversationId = conversationId;
    this.conversation = conversation;
    this.activeRunId = null;
    /** @type {Map<string, number>} runId -> text chars already emitted */
    this.emittedText = new Map();
    /** @type {Map<string, number>} runId -> last seen generation attempt */
    this.attempts = new Map();
    /** @type {Map<string, {resolve, reject, runId, name}>} toolCallId -> pending */
    this.pendingTools = new Map();
    this.watchStarted = false;
  }
}

function emitEvent(sessionId, event) {
  const stamped = journal.appendEvent(sessionId, event);
  send(stamped);
  return stamped;
}

function emitFinishedOnce(sessionId, runId, outcome) {
  if (journal.hasFinished(runId)) return false;
  emitEvent(sessionId, { type: 'finished', runId, ...outcome });
  return true;
}

function mapSettlement(settled) {
  if (settled.status === 'done') return { status: 'SUCCEEDED' };
  const reason = settled.reason;
  if (reason === 'aborted') return { status: 'CANCELLED', reason };
  if (reason === 'model_error' || reason === 'no_model') return { status: 'FAILED', errorCode: 'PI_MODEL_ERROR', reason };
  if (reason === 'turn_limit') return { status: 'FAILED', errorCode: 'AGENT_BUDGET_EXCEEDED', reason };
  return { status: 'FAILED', errorCode: 'PI_WORKER_ERROR', reason };
}

function assistantText(message) {
  return (message?.content ?? [])
    .filter((block) => block?.type === 'text')
    .map((block) => block.text ?? '')
    .join('');
}

function handleView(session, value) {
  const runId = session.activeRunId;
  if (!runId) return;
  const live = value.docs?.['pi.live'];
  const generation = live?.generation;
  const message = generation?.message;
  if (!generation || !message) return;
  if (session.primeOnNextView) {
    // Recovered run: the view's first value may still carry the partial of the
    // attempt the last process died in. Adopt it as the baseline without
    // emitting; the retry's attempt bump then triggers a clean text_reset.
    session.primeOnNextView = false;
    session.attempts.set(runId, generation.attempt ?? 1);
    session.emittedText.set(runId, assistantText(message).length);
    return;
  }
  // A crash mid-answer makes the attempt a failed one; durable retries it and
  // the partial restarts from scratch. Already-sent deltas cannot be unsent,
  // so the host is told to drop what it has and reassemble from here.
  const attempt = generation.attempt ?? 1;
  const lastAttempt = session.attempts.get(runId) ?? attempt;
  if (attempt !== lastAttempt) {
    session.attempts.set(runId, attempt);
    if ((session.emittedText.get(runId) ?? 0) > 0) {
      emitEvent(session.sessionId, { type: 'text_reset', runId });
    }
    session.emittedText.set(runId, 0);
  }
  const text = assistantText(message);
  const emitted = session.emittedText.get(runId) ?? 0;
  if (text.length < emitted) return; // partial not past what we sent yet
  if (text.length > emitted) {
    emitEvent(session.sessionId, { type: 'text_delta', runId, delta: text.slice(emitted) });
    session.emittedText.set(runId, text.length);
  }
}

async function ensureWatch(session) {
  if (session.watchStarted) return;
  session.watchStarted = true;
  const watch = await session.conversation.watch(ctx);
  watch.start(async (call) => {
    if (call.kind !== 'value') return;
    try {
      handleView(session, call.value);
    } catch (error) {
      logDiagnostic('view handler error:', error);
    }
  });
}

/** Emit the answer's not-yet-streamed tail so the run's text is complete. */
async function flushAnswerText(session, runId, answerEntryId) {
  try {
    const page = await session.conversation.entries(
      { minEntryId: answerEntryId, maxEntryId: answerEntryId, order: 'ascending' },
      1,
      undefined,
      ctx,
    );
    const entry = page.items[0];
    const text = assistantText(entry?.model?.[0]);
    const emitted = session.emittedText.get(runId) ?? 0;
    if (text.length > emitted) {
      emitEvent(session.sessionId, { type: 'text_delta', runId, delta: text.slice(emitted) });
      session.emittedText.set(runId, text.length);
    }
  } catch (error) {
    logDiagnostic('answer tail flush failed:', error);
  }
}

async function settleRun(session, runId, submission) {
  let settled;
  try {
    settled = await submission.wait(ctx);
  } catch (error) {
    logDiagnostic('submission wait failed:', error);
    if (session.activeRunId === runId) session.activeRunId = null;
    emitFinishedOnce(session.sessionId, runId, { status: 'FAILED', errorCode: 'PI_WORKER_ERROR' });
    return;
  }
  if (settled.status === 'done' && settled.answer !== undefined) {
    await flushAnswerText(session, runId, settled.answer);
  }
  if (session.activeRunId === runId) session.activeRunId = null;
  emitFinishedOnce(session.sessionId, runId, mapSettlement(settled));
}

/**
 * The tool dispatch handed to every tool definition: emit tool_call, then wait
 * for the host's tool_result. After a crash the pi.tool task reruns this (the
 * tools are replay:"safe"), re-emitting the same callId.
 */
function dispatchTool(name, args, api, context) {
  return new Promise((resolve, reject) => {
    const session = sessionsByConversation.get(api.conversationId);
    const runId = session?.activeRunId;
    if (!session || !runId) {
      reject(new Error('no active run for tool call'));
      return;
    }
    session.pendingTools.set(api.callId, { resolve, reject, runId, name });
    emitEvent(session.sessionId, {
      type: 'tool_call',
      runId,
      toolCallId: api.callId,
      name,
      arguments: JSON.stringify(args ?? {}),
    });
    context.abortSignal?.addEventListener(
      'abort',
      () => {
        if (session.pendingTools.delete(api.callId)) reject(new Error('aborted'));
      },
      { once: true },
    );
  });
}

const registry = createRegistry();
registry.install(defineExtension({ name: 'data-agent', tools: buildTools(dispatchTool) }));
const allTools = registry.snapshot().tools().map((entry) => entry.tool);

function validateStart(msg) {
  if (typeof msg.runId !== 'string' || !msg.runId) return 'runId required';
  if (typeof msg.sessionId !== 'string' || !msg.sessionId) return 'sessionId required';
  if (typeof msg.systemPrompt !== 'string' || msg.systemPrompt.length > 64000) return 'systemPrompt invalid';
  if (typeof msg.message !== 'string' || !msg.message || msg.message.length > 128000) return 'message invalid';
  if (typeof msg.apiKey !== 'string' || !msg.apiKey) return 'apiKey required';
  if (typeof msg.model !== 'object' || msg.model === null) return 'model required';
  if (msg.idempotencyKey !== undefined && typeof msg.idempotencyKey !== 'string') return 'idempotencyKey invalid';
  if (msg.role !== undefined && !['assistant', 'explorer'].includes(msg.role)) return 'role invalid';
  if (msg.toolProfile !== undefined && !['general', 'schema'].includes(msg.toolProfile)) return 'toolProfile invalid';
  const maxModelCalls = msg.limits?.maxModelCalls;
  if (maxModelCalls !== undefined && (!Number.isInteger(maxModelCalls) || maxModelCalls < 1 || maxModelCalls > 64)) {
    return 'limits invalid';
  }
  return null;
}

async function getOrCreateSession(sessionId) {
  let session = sessions.get(sessionId);
  if (session) return session;
  const known = journal.sessions.get(sessionId);
  let conversation;
  if (known) conversation = await harness.conversation(known.conversationId, ctx);
  if (!conversation) {
    conversation = await harness.createConversation({ ownership: { kind: 'ownerless' } }, ctx);
    journal.registerSession(sessionId, conversation.id);
  }
  session = new SessionState(sessionId, conversation.id, conversation);
  sessions.set(sessionId, session);
  sessionsByConversation.set(conversation.id, session);
  return session;
}

async function handleStart(msg) {
  const sessionId = msg.sessionId;
  const runId = msg.runId;

  // Credentials live only in this process: a recovered run can only continue
  // once the host re-sends start (same idempotencyKey) to re-register the
  // model. Registering is idempotent and cheap, so do it on every start,
  // including re-sent ones.
  let modelRef;
  try {
    modelRef = modelRegistry.register(msg.model, msg.apiKey);
  } catch (error) {
    emitFinishedOnce(sessionId, runId, { status: 'FAILED', errorCode: error.code ?? 'PI_WORKER_ERROR' });
    return;
  }

  const knownRun = journal.runs.get(runId);
  if (knownRun) {
    // Idempotent re-send of a start we already admitted (requestId dedupe in
    // pi-durable backs this): attach to the existing run, replay its journaled
    // events, and never submit twice.
    logDiagnostic(`duplicate start for run ${runId}; replaying journaled events`);
    const session = await getOrCreateSession(sessionId);
    for (const entry of journal.eventsAfter(sessionId, 0)) {
      if (entry.event.runId === runId) send(entry.event);
    }
    if (!journal.hasFinished(runId) && session.activeRunId !== runId) {
      const submission = await harness.submission(knownRun.submissionId, ctx);
      if (submission) {
        session.activeRunId = runId;
        await ensureWatch(session);
        void settleRun(session, runId, submission);
      }
    }
    return;
  }

  let session;
  try {
    session = await getOrCreateSession(sessionId);
  } catch (error) {
    logDiagnostic('session open failed:', error);
    emitFinishedOnce(sessionId, runId, { status: 'FAILED', errorCode: 'PI_WORKER_ERROR' });
    return;
  }

  try {
    await session.conversation.configure(
      {
        model: modelRef,
        instructions: msg.systemPrompt,
        tools: toolsForRole(allTools, msg.role ?? 'assistant', msg.toolProfile ?? 'general'),
        maxTurns: msg.limits?.maxModelCalls ?? null,
      },
      ctx,
    );
  } catch (error) {
    logDiagnostic('configure failed:', error);
    emitFinishedOnce(sessionId, runId, { status: 'FAILED', errorCode: 'PI_WORKER_ERROR' });
    return;
  }

  const requestId = msg.idempotencyKey ?? runId;
  let submission;
  try {
    submission = await session.conversation.submit(
      { type: 'input', content: msg.message, requestId, whenBusy: 'reject' },
      ctx,
    );
  } catch (error) {
    if (error instanceof ConversationBusy) {
      emitFinishedOnce(sessionId, runId, { status: 'FAILED', errorCode: 'PI_SESSION_BUSY' });
      return;
    }
    logDiagnostic('submit failed:', error);
    emitFinishedOnce(sessionId, runId, { status: 'FAILED', errorCode: 'PI_WORKER_ERROR' });
    return;
  }
  journal.registerRun(sessionId, runId, submission.id, requestId, msg.model);
  runsById.set(runId, sessionId);
  session.activeRunId = runId;
  session.emittedText.set(runId, 0);
  await ensureWatch(session);
  void settleRun(session, runId, submission);
}

function handleToolResult(msg) {
  const sessionId = runsById.get(msg.runId);
  const session = sessionId ? sessions.get(sessionId) : undefined;
  const pending = session?.pendingTools.get(msg.toolCallId);
  if (!session || !pending || pending.runId !== msg.runId) {
    logDiagnostic('late tool_result dropped:', msg.runId, msg.toolCallId);
    return;
  }
  session.pendingTools.delete(msg.toolCallId);
  const text = typeof msg.result === 'string' ? msg.result : String(msg.result ?? '');
  pending.resolve({ text, isError: msg.isError === true });
}

async function handleCancel(msg) {
  const sessionId = runsById.get(msg.runId);
  const session = sessionId ? sessions.get(sessionId) : undefined;
  if (!session || session.activeRunId !== msg.runId) return;
  try {
    // The abort is committed to durable storage before it takes effect, so a
    // kill after this point cannot revive the run.
    await session.conversation.abort(ctx);
  } catch (error) {
    logDiagnostic('abort failed:', error);
    emitFinishedOnce(session.sessionId, msg.runId, { status: 'FAILED', errorCode: 'PI_WORKER_ERROR' });
  }
}

function handleAttach(msg) {
  const events = journal.eventsAfter(msg.sessionId, Number.isInteger(msg.afterSeq) ? msg.afterSeq : 0);
  for (const entry of events) send(entry.event);
  send({ type: 'attached', sessionId: msg.sessionId, replayed: events.length });
}

let harness;

async function recover() {
  for (const [sessionId, info] of journal.sessions) {
    const conversation = await harness.conversation(info.conversationId, ctx);
    if (!conversation) {
      logDiagnostic(`stored conversation ${info.conversationId} of session ${sessionId} is gone`);
      continue;
    }
    const session = new SessionState(sessionId, conversation.id, conversation);
    sessions.set(sessionId, session);
    sessionsByConversation.set(conversation.id, session);
  }
  for (const [runId, run] of journal.runs) {
    runsById.set(runId, run.sessionId);
    if (run.finished) continue;
    const session = sessions.get(run.sessionId);
    if (!session) continue;
    // Re-register the model WITHOUT its credential before any retry can fire:
    // the provider resolves, and auth resolution gates until the host re-sends
    // start (bounded by MODEL_GATE_MS), instead of the run failing no_model
    // against the fixed ~2s retry backoff.
    if (run.model) {
      try {
        modelRegistry.registerShell(run.model);
      } catch (error) {
        logDiagnostic('model shell registration failed:', error);
      }
    }
    const submission = await harness.submission(run.submissionId, ctx);
    if (!submission) {
      emitFinishedOnce(session.sessionId, runId, { status: 'FAILED', errorCode: 'PI_WORKER_ERROR', reason: 'submission_lost' });
      continue;
    }
    session.activeRunId = runId;
    session.emittedText.set(runId, 0);
    session.primeOnNextView = true;
    // We no longer know how much of the in-flight answer the host assembled
    // before the restart; tell it to drop partial text and reassemble from
    // the deltas that follow.
    if (journal.eventsAfter(session.sessionId, 0).some((entry) => entry.event.type === 'text_delta' && entry.event.runId === runId)) {
      emitEvent(session.sessionId, { type: 'text_reset', runId });
    }
    await ensureWatch(session);
    void settleRun(session, runId, submission);
  }
  harness.resume();
}

async function onMessage(msg) {
  if (typeof msg !== 'object' || msg === null || typeof msg.type !== 'string') {
    send({ type: 'error', errorCode: 'PI_PROTOCOL_ERROR', detail: 'message must have a type' });
    return;
  }
  try {
    switch (msg.type) {
      case 'start': {
        const problem = validateStart(msg);
        if (problem) {
          send({
            type: 'finished',
            runId: typeof msg.runId === 'string' ? msg.runId : 'unknown',
            sessionId: typeof msg.sessionId === 'string' ? msg.sessionId : 'unknown',
            status: 'FAILED',
            errorCode: 'PI_PROTOCOL_ERROR',
          });
          logDiagnostic('rejected start:', problem);
          return;
        }
        await handleStart(msg);
        return;
      }
      case 'tool_result': {
        if (typeof msg.runId !== 'string' || typeof msg.toolCallId !== 'string') return;
        handleToolResult(msg);
        return;
      }
      case 'cancel': {
        if (typeof msg.runId !== 'string') return;
        await handleCancel(msg);
        return;
      }
      case 'attach': {
        if (typeof msg.sessionId !== 'string') return;
        handleAttach(msg);
        return;
      }
      default:
        send({ type: 'error', errorCode: 'PI_PROTOCOL_ERROR', detail: `unknown type ${msg.type}`.slice(0, 200) });
    }
  } catch (error) {
    logDiagnostic('message handler failed:', error);
    send({ type: 'error', errorCode: 'PI_WORKER_ERROR', detail: String(error?.message ?? error).slice(0, 200) });
  }
}

function isLockError(error) {
  const message = String(error?.message ?? error);
  return message.includes('database is locked') || message.includes('SQLITE_BUSY');
}

async function openStorage() {
  if (STORAGE_KIND === 'memory') return new MemoryStorage();
  // Boot-time lock contention is transient (e.g. two workers started at once
  // on the same file, one running schema setup): retry briefly before giving
  // up. A persistent owner outlasts the window and gets the diagnostic below.
  const maxAttempts = 20; // 20 x 250ms = 5s, matching SQLITE_BUSY_TIMEOUT_MS
  for (let attempt = 1; ; attempt++) {
    try {
      return await openNodeSqliteStorage(SQLITE_PATH, { busyTimeoutMs: SQLITE_BUSY_TIMEOUT_MS });
    } catch (error) {
      if (isLockError(error) && attempt < maxAttempts) {
        await new Promise((resolve) => setTimeout(resolve, 250));
        continue;
      }
      // pi-durable supports exactly one live owner per storage file and there is
      // no cross-process locking; surface that as an actionable diagnostic.
      throw new Error(
        `cannot open sqlite storage at ${SQLITE_PATH} (busy timeout ${SQLITE_BUSY_TIMEOUT_MS}ms, ` +
          `retried ${attempt - 1}x; another live worker may hold this file, or the path is not writable): ` +
          `${error?.message ?? error}`,
        { cause: error },
      );
    }
  }
}

async function main() {
  const storage = await openStorage();
  harness = await Harness.open(
    storage,
    {
      models: modelRegistry.models,
      registry,
      settings: {
        compaction: { enabled: false }, // compaction is out of scope for this prototype
        afterFailedRun: 'keep',
      },
      onReport: (report) => logDiagnostic(`report ${report.kind}:`, report.error ?? ''),
    },
    ctx,
  );
  await recover();
  send({
    type: 'hello',
    protocol: PROTOCOL_VERSION,
    piDurable: '1.5.0',
    piAi: typeof PI_AI_VERSION === 'string' ? PI_AI_VERSION : '1.5.0',
    storage: STORAGE_KIND,
    recoveredRuns: journal.recoveredRuns(),
  });
  readJsonLines(process.stdin, {
    maxBytes: MAX_INBOUND_LINE_BYTES,
    onMessage: (msg) => void onMessage(msg),
    onViolation: (error) => {
      logDiagnostic('protocol violation:', error);
      send({ type: 'error', errorCode: 'PI_PROTOCOL_ERROR', detail: error.message.slice(0, 200) });
    },
    onEnd: () => shutdown(0),
  });
}

let closing = false;
function shutdown(code) {
  if (closing) return;
  closing = true;
  const done = () => process.exit(code);
  if (!harness) return done();
  // A graceful close commits deferred progress; it does not count as a crash.
  Promise.race([harness.close(ctx), new Promise((resolve) => setTimeout(resolve, 5000))]).then(done, done);
}

process.on('uncaughtException', (error) => {
  logDiagnostic('uncaughtException:', error);
  process.exit(1);
});
process.on('unhandledRejection', (error) => {
  logDiagnostic('unhandledRejection:', error);
  process.exit(1);
});
process.on('SIGTERM', () => shutdown(0));
process.on('SIGINT', () => shutdown(0));

main().catch((error) => {
  // A boot failure (storage open, harness recovery) must never exit silently:
  // the host only observes the exit code, so the reason goes to stderr.
  logDiagnostic('boot failed:', error);
  process.exit(1);
});
