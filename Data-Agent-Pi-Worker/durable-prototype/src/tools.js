/**
 * Host-executed tools. The durable worker never executes a tool itself: every
 * execute() emits a `tool_call` outbound event and suspends until the host's
 * `tool_result` arrives (or the call is aborted). replay: "safe" makes the
 * pi.tool task rerun execute() after a process crash, which re-emits the same
 * toolCallId — that rerun is the core mechanism under test (T2 mechanism 1).
 *
 * Parameters are plain JSON Schema (validated by pi-ai's validateToolArguments).
 */
import { defineTool } from '@earendil-works/pi-durable';
import { MAX_TOOL_RESULT_CHARS } from './protocol.js';

const targetFields = {
  connectionId: { type: 'number', description: 'Connection ID from list_connections, the turn context or an explicit reference.' },
  catalogName: { type: 'string', description: 'Catalog/database name, when applicable.' },
  schemaName: { type: 'string', description: 'Schema name, when applicable.' },
};

const DEFINITIONS = [
  {
    name: 'list_tables',
    description: 'List tables in one known database/schema.',
    parameters: { type: 'object', properties: { ...targetFields } },
  },
  {
    name: 'describe_table',
    description: 'Read column metadata for one table.',
    parameters: {
      type: 'object',
      properties: { tableName: { type: 'string', minLength: 1, maxLength: 128 }, ...targetFields },
      required: ['tableName'],
    },
  },
  {
    name: 'describe_tables',
    description: 'Read compact columns, primary keys and foreign keys for 1-4 named tables.',
    parameters: {
      type: 'object',
      properties: {
        tableNames: { type: 'array', items: { type: 'string', minLength: 1, maxLength: 128 }, minItems: 1, maxItems: 4 },
        ...targetFields,
      },
      required: ['tableNames'],
    },
  },
  {
    name: 'query_database',
    description: 'Execute one supported read-only SELECT/WITH through the Java host.',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', minLength: 1, maxLength: 65536 },
        ...targetFields,
      },
      required: ['query'],
    },
  },
  {
    name: 'list_connections',
    description: 'List available data sources with connectionId, name and dbType.',
    parameters: { type: 'object', properties: {} },
  },
  {
    name: 'list_namespaces',
    description: 'Discover catalogs/schemas on one connection.',
    parameters: { type: 'object', properties: { ...targetFields } },
  },
  {
    name: 'callingExplorerSubAgent',
    description: 'Delegate focused database discovery tasks to Explorer agents.',
    parameters: {
      type: 'object',
      properties: {
        tasks: {
          type: 'array',
          minItems: 1,
          maxItems: 2,
          items: {
            type: 'object',
            properties: {
              connectionId: { type: 'number' },
              catalogName: { type: 'string' },
              schemaName: { type: 'string' },
              instruction: { type: 'string', minLength: 1, maxLength: 4000 },
              mode: { type: 'string', enum: ['schema', 'general'] },
              context: { type: 'string', maxLength: 4000 },
              timeoutSeconds: { type: 'integer', minimum: 1, maximum: 45 },
            },
            required: ['connectionId', 'instruction'],
          },
        },
      },
      required: ['tasks'],
    },
  },
];

/**
 * Build the tool registrations. `dispatch(name, args, api, context)` must emit
 * the tool_call event and return a promise settling with {text, isError}.
 */
export function buildTools(dispatch) {
  return DEFINITIONS.map((def) =>
    defineTool({
      name: def.name,
      description: def.description,
      parameters: def.parameters,
      replay: 'safe',
      execute: async (args, api, context) => {
        const settled = await dispatch(def.name, args ?? {}, api, context);
        const capped =
          settled.text.length > MAX_TOOL_RESULT_CHARS
            ? `${settled.text.slice(0, MAX_TOOL_RESULT_CHARS)}…[truncated]`
            : settled.text;
        return { output: [{ type: 'text', text: capped }], isError: settled.isError === true };
      },
    }),
  );
}

/** Same role/profile filtering as the v1 worker. */
export function toolsForRole(allTools, role = 'assistant', profile = 'general') {
  let tools = allTools;
  if (role === 'explorer') {
    tools = tools.filter((tool) => !['list_connections', 'list_namespaces', 'callingExplorerSubAgent'].includes(tool.name));
  }
  if (role === 'explorer' && profile === 'schema') {
    tools = tools.filter((tool) => ['list_tables', 'describe_tables'].includes(tool.name));
  }
  return tools;
}
