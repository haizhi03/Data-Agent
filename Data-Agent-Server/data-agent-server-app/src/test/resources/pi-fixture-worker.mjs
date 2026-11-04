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

send({ type: 'hello', protocol: 1, piAi: 'fixture' });

function finish(runId, status, extra = {}) {
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
  const text = msg.message ?? '';
  const turn = (turns.get(sessionId) ?? 0) + 1;
  turns.set(sessionId, turn);

  if (text.includes('crash-now')) {
    process.exit(2);
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
  const sessionId = pendingRuns.get(msg.runId);
  if (sessionId === undefined) return;
  pendingRuns.delete(msg.runId);
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
