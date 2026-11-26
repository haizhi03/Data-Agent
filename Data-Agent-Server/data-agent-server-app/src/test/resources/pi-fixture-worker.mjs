/**
 * Deterministic protocol fixture for Java integration tests. Speaks the same
 * stdio JSON-lines protocol as the real pi-worker but never loads pi-agent-core
 * and never calls a model. Behavior is keyed on keywords inside the start
 * message; see LocalPiChatHttpTest.
 */
import readline from 'node:readline';

const send = (message) => process.stdout.write(JSON.stringify(message) + '\n');
const turns = new Map(); // sessionId -> completed run count
const pendingRuns = new Map(); // runId -> sessionId (waiting for tool_result or cancel)
const queryRuns = new Set();
const explorerRuns = new Map();

send({ type: 'hello', protocol: 1, piAi: 'fixture' });

function finish(runId, status, extra = {}) {
  queryRuns.delete(runId);
  send({ type: 'finished', runId, status,
    usage: { modelCalls: 1, toolCalls: 1, inputTokens: 11, outputTokens: 7, usageComplete: true }, ...extra });
}

function mentionArgs(msg, token) {
  const mention = (msg.context?.mentions ?? []).find((m) => m.token === token);
  if (!mention) return { tableName: 'missing' };
  const args = { tableName: mention.objectName, connectionId: mention.connectionId };
  if (mention.catalogName) args.catalogName = mention.catalogName;
  if (mention.schemaName) args.schemaName = mention.schemaName;
  return args;
}

function onStart(msg) {
  const { runId, sessionId } = msg;
  if (msg.role === 'explorer') {
    const task = JSON.parse(msg.message);
    if (task.instruction === 'crash') process.exit(2);
    explorerRuns.set(runId, { ...task, modelId: msg.model.modelId });
    if (task.instruction === 'wait') return;
    const args = task.instruction.startsWith('escape:') ? JSON.parse(task.instruction.slice(7)) : {};
    const query = task.instruction === 'wait-query' || task.instruction === 'query';
    if (task.instruction === 'wait-query') args.query = 'SELECT COUNT(*) FROM safe_table /* wait-query */';
    else if (query) args.query = 'SELECT COUNT(*) FROM safe_table';
    if (task.instruction === 'batch-read') args.tableNames = ['safe_table', 'second_table'];
    send({ type: 'tool_call', runId, toolCallId: 'child_read', name: task.instruction === 'batch-read' ? 'describe_tables' : query ? 'query_database' : 'list_tables', arguments: JSON.stringify(args) });
    return;
  }
  const encoded = msg.message ?? '';
  const text = encoded.startsWith('[Data-Agent turn context v1]\n')
    ? JSON.parse(encoded.slice(encoded.indexOf('\n') + 1)).message : encoded;
  const turn = (turns.get(sessionId) ?? 0) + 1;
  turns.set(sessionId, turn);

  for (const name of ['list_connections', 'list_namespaces', 'list_tables']) {
    if (text.startsWith(name + ':')) {
      pendingRuns.set(runId, sessionId);
      send({ type: 'tool_call', runId, toolCallId: 'call_discovery', name, arguments: text.slice(name.length + 1) });
      return;
    }
  }

  if (text.startsWith('batch-json:')) {
    pendingRuns.set(runId, sessionId);
    send({ type: 'tool_call', runId, toolCallId: 'call_batch', name: 'describe_tables', arguments: text.slice('batch-json:'.length) });
    return;
  }
  if (text.startsWith('explore-json:')) {
    pendingRuns.set(runId, sessionId);
    send({ type: 'tool_call', runId, toolCallId: 'call_explorer', name: 'callingExplorerSubAgent', arguments: text.slice('explore-json:'.length) });
    return;
  }

  if (text === 'model-error-diagnostics') {
    finish(runId, 'FAILED', { errorCode: 'PI_MODEL_ERROR', modelError: {
      kind: 'AUTH', httpStatus: 401, elapsedMs: 123,
      detail: msg.apiKey, requestId: msg.apiKey,
    } });
    return;
  }
  if (text === 'model-error-invalid-diagnostics') {
    finish(runId, 'FAILED', { errorCode: 'PI_MODEL_ERROR', modelError: {
      kind: msg.apiKey, httpStatus: msg.apiKey, elapsedMs: -1,
    } });
    return;
  }
  if (text.includes('crash-now')) {
    process.exit(2);
  }
  if (text.includes('invalid-stdout')) {
    process.stdout.write('worker protocol noise\n');
    return;
  }
  if (text.includes('stderr-secret')) {
    process.stderr.write(`provider diagnostic with credential ${msg.apiKey}\n`);
  }
  if (text.includes('wait-for-cancel')) {
    pendingRuns.set(runId, sessionId);
    return;
  }
  if (text.includes('fail-tool')) {
    pendingRuns.set(runId, sessionId);
    send({ type: 'tool_call', runId, toolCallId: 'call_bad', name: 'drop_everything', arguments: '{}' });
    return;
  }
  if (text.startsWith('query-json:') || text.includes('query-mention')) {
    pendingRuns.set(runId, sessionId);
    const args = text.startsWith('query-json:') ? JSON.parse(text.slice('query-json:'.length)) : mentionArgs(msg, '@orders');
    if (text.includes('query-mention')) { delete args.tableName; args.query = 'SELECT COUNT(*) FROM orders'; }
    send({ type: 'tool_call', runId, toolCallId: 'call_query', name: 'query_database', arguments: JSON.stringify(args) });
    if (text.includes('crash-query')) setTimeout(() => process.exit(2), 250);
    return;
  }
  if (text.includes('discover-query')) queryRuns.add(runId);
  if (text.includes('@orders')) {
    pendingRuns.set(runId, sessionId);
    send({ type: 'tool_call', runId, toolCallId: 'call_describe', name: 'describe_table',
      arguments: JSON.stringify(mentionArgs(msg, '@orders')) });
    return;
  }
  if (text.includes('discover')) {
    pendingRuns.set(runId, sessionId);
    send({ type: 'tool_call', runId, toolCallId: 'call_list', name: 'list_tables', arguments: '{}' });
    return;
  }
  send({ type: 'text_delta', runId, delta: `pong-turn${turn}` });
  if (text.includes('late-events')) {
    finish(runId, 'SUCCEEDED');
    // Late events after the terminal message must be ignored by the host.
    send({ type: 'text_delta', runId, delta: 'LATE-DELTA' });
    send({ type: 'finished', runId, status: 'FAILED', errorCode: 'PI_WORKER_ERROR' });
    return;
  }
  finish(runId, 'SUCCEEDED');
}

function onToolResult(msg) {
  if (explorerRuns.has(msg.runId)) {
    const task = explorerRuns.get(msg.runId); explorerRuns.delete(msg.runId);
    const value = JSON.parse(msg.result);
    const first = value.tables?.[0];
    const name = task.instruction === 'invent-object' ? 'invented_table' : typeof first === 'object' ? first.table : first;
    send({ type: 'text_delta', runId: msg.runId, delta: JSON.stringify({ summaryText: `Explorer model=${task.modelId}; error=${msg.isError}; result=${msg.result}`,
      objects: name ? [{ objectName: name, objectType: 'TABLE', objectDdl: null, relevanceScore: 90 }] : [] }) });
    finish(msg.runId, 'SUCCEEDED'); return;
  }
  const sessionId = pendingRuns.get(msg.runId);
  if (sessionId === undefined) return;
  pendingRuns.delete(msg.runId);
  if (msg.toolCallId === 'call_explorer') {
    send({ type: 'text_delta', runId: msg.runId, delta: `explorer-envelope:${msg.isError}:${msg.result}` });
    finish(msg.runId, 'SUCCEEDED'); return;
  }
  if (msg.toolCallId === 'call_bad') {
    send({ type: 'text_delta', runId: msg.runId, delta: `tool-error-seen:${msg.isError}:${msg.result}` });
    finish(msg.runId, 'SUCCEEDED');
    return;
  }
  if (msg.toolCallId === 'call_list' && !msg.isError) {
    try {
      const result = JSON.parse(msg.result);
      const first = result.tables?.[0] ?? 'none';
      send({ type: 'tool_call', runId: msg.runId, toolCallId: 'call_describe', name: 'describe_table',
        arguments: JSON.stringify({ tableName: first }) });
      pendingRuns.set(msg.runId, sessionId);
      return;
    } catch { /* fall through to generic */ }
  }
  if (msg.toolCallId === 'call_describe' && queryRuns.delete(msg.runId) && !msg.isError) {
    pendingRuns.set(msg.runId, sessionId);
    send({ type: 'tool_call', runId: msg.runId, toolCallId: 'call_query', name: 'query_database',
      arguments: JSON.stringify({ query: 'SELECT COUNT(*) FROM safe_table' }) });
    return;
  }
  if (msg.toolCallId === 'call_query') {
    send({ type: 'text_delta', runId: msg.runId, delta: `query-result:${msg.isError}:${msg.result}` });
    finish(msg.runId, 'SUCCEEDED');
    return;
  }
  send({ type: 'text_delta', runId: msg.runId, delta: `tool-result:${msg.isError}:${String(msg.result).slice(0, 400)}` });
  finish(msg.runId, 'SUCCEEDED');
}

const lines = readline.createInterface({ input: process.stdin });
lines.on('line', (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  if (msg.type === 'start') onStart(msg);
  else if (msg.type === 'tool_result') onToolResult(msg);
  else if (msg.type === 'cancel') {
    if (pendingRuns.delete(msg.runId) !== false) finish(msg.runId, 'CANCELLED');
  }
});
lines.on('close', () => process.exit(0));
