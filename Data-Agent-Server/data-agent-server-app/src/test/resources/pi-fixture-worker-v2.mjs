/**
 * Deterministic protocol v2 (durable) fixture for Java integration tests. Speaks the
 * v2 stdio JSON-lines contract: per-session seq on every run event, text_reset,
 * idempotent start via idempotencyKey, attach/attached replay, and crash recovery
 * driven by a JSON state file that acts as the durable store.
 *
 * State file: PI_FIXTURE_V2_STATE env var, else <tmpdir>/pi-fixture-worker-v2-state.json.
 * Tests point the env var at a per-run temp file through a small wrapper script.
 * Behavior is keyed on keywords inside the start message; see LocalPiProtocolV2HttpTest.
 * No model calls, no pi-agent-core.
 */
import readline from 'node:readline';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const stateFile = process.env.PI_FIXTURE_V2_STATE || path.join(os.tmpdir(), 'pi-fixture-worker-v2-state.json');

function load() {
  try { return JSON.parse(fs.readFileSync(stateFile, 'utf8')); } catch { return null; }
}
const state = load() || { boots: 0, runs: {}, sessions: {}, inbound: [] };
state.boots += 1;
function save() {
  fs.writeFileSync(stateFile, JSON.stringify(state));
}
save();

const send = (message) => process.stdout.write(JSON.stringify(message) + '\n');

function sessionOf(id) {
  if (!state.sessions[id]) state.sessions[id] = { seq: 0, log: [] };
  return state.sessions[id];
}

/** Run-scoped v2 event: sessionId + per-session increasing seq, logged for attach replay. */
function emit(run, event) {
  const session = sessionOf(run.sessionId);
  session.seq += 1;
  const full = { ...event, sessionId: run.sessionId, runId: run.runId, seq: session.seq };
  session.log.push(full);
  save();
  send(full);
}

function finish(run, status, extra = {}) {
  run.status = status;
  run.pendingToolCall = null;
  emit(run, { type: 'finished', status, ...extra });
  save();
}

function recordInbound(msg) {
  state.inbound.push({ boot: state.boots, type: msg.type, runId: msg.runId ?? null, sessionId: msg.sessionId ?? null });
  save();
}

send({
  type: 'hello', protocol: 2, piDurable: '1.5.0', piAi: '1.5.0', storage: 'sqlite',
  recoveredRuns: Object.values(state.runs)
    .filter((run) => run.status === 'RUNNING')
    .map((run) => ({ sessionId: run.sessionId, runId: run.runId, status: run.status })),
});

// Autonomous resume: a durable worker re-emits the tool_call each recovered run was
// waiting on, immediately after hello, before any start/attach arrives.
for (const run of Object.values(state.runs)) {
  if (run.status === 'RUNNING' && run.pendingToolCall) {
    emit(run, { type: 'tool_call', ...run.pendingToolCall });
  }
}

function extractText(message) {
  const encoded = message ?? '';
  return encoded.startsWith('[Data-Agent turn context v1]\n')
    ? JSON.parse(encoded.slice(encoded.indexOf('\n') + 1)).message
    : encoded;
}

function onStart(msg) {
  recordInbound(msg);
  const existing = state.runs[msg.runId];
  if (existing && msg.idempotencyKey && existing.idempotencyKey === msg.idempotencyKey) {
    // Credential re-attach: same idempotencyKey, do not restart the run.
    existing.reattached = (existing.reattached ?? 0) + 1;
    save();
    if (existing.behavior === 'crash-always') process.exit(2);
    return;
  }
  const run = {
    runId: msg.runId, sessionId: msg.sessionId, idempotencyKey: msg.idempotencyKey ?? null,
    status: 'RUNNING', pendingToolCall: null, behavior: null,
  };
  state.runs[msg.runId] = run;
  save();
  const text = extractText(msg.message);

  if (text.includes('crash-always')) {
    run.behavior = 'crash-always';
    save();
    process.exit(2);
  }
  if (text.includes('crash-after-result')) {
    // Ask for a tool, then die right after the host answers, before consuming the
    // result. After recovery the run is still RUNNING with the same toolCallId pending.
    run.behavior = 'crash-after-result';
    run.pendingToolCall = { toolCallId: 'call_1', name: 'list_tables', arguments: '{}' };
    save();
    emit(run, { type: 'tool_call', ...run.pendingToolCall });
    return;
  }
  if (text.includes('cancel-loss')) {
    // Stay pending on the tool; the first cancel is "lost" (process dies before it can
    // settle the run in storage). The next process must settle it when cancelled again.
    run.behavior = 'cancel-loss';
    run.cancelLost = true;
    run.pendingToolCall = { toolCallId: 'call_1', name: 'list_tables', arguments: '{}' };
    save();
    emit(run, { type: 'tool_call', ...run.pendingToolCall });
    return;
  }
  if (text.includes('reset-text')) {
    emit(run, { type: 'text_delta', delta: 'stale' });
    emit(run, { type: 'text_reset' });
    emit(run, { type: 'text_delta', delta: 'fresh' });
    finish(run, 'SUCCEEDED');
    return;
  }
  if (text.includes('dupe-events')) {
    emit(run, { type: 'text_delta', delta: 'once' });
    // Raw replay duplicates with the same seq: the host must deliver them only once.
    const session = sessionOf(run.sessionId);
    send({ type: 'text_delta', sessionId: run.sessionId, runId: run.runId, seq: session.seq, delta: 'once' });
    finish(run, 'SUCCEEDED');
    send({ type: 'finished', sessionId: run.sessionId, runId: run.runId, seq: session.seq, status: 'FAILED', errorCode: 'PI_WORKER_ERROR' });
    return;
  }
  emit(run, { type: 'text_delta', delta: 'pong-v2' });
  finish(run, 'SUCCEEDED');
}

function onToolResult(msg) {
  recordInbound(msg);
  const run = state.runs[msg.runId];
  if (!run || run.status !== 'RUNNING') return;
  if (run.behavior === 'cancel-loss') return; // never settles on a tool result
  if (run.behavior === 'crash-after-result' && !run.resultSeen) {
    run.resultSeen = true; // result arrived but was never consumed
    save();
    process.exit(2);
  }
  if (run.pendingToolCall && run.pendingToolCall.toolCallId === msg.toolCallId) {
    run.pendingToolCall = null;
    emit(run, { type: 'text_delta', delta: `tool-answer:${msg.isError}:${String(msg.result).slice(0, 200)}` });
    finish(run, 'SUCCEEDED');
  }
}

const lines = readline.createInterface({ input: process.stdin });
lines.on('line', (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  if (msg.type === 'start') onStart(msg);
  else if (msg.type === 'tool_result') onToolResult(msg);
  else if (msg.type === 'attach') {
    recordInbound(msg);
    const session = state.sessions[msg.sessionId];
    const afterSeq = Number.isInteger(msg.afterSeq) ? msg.afterSeq : 0;
    const replay = session ? session.log.filter((event) => event.seq > afterSeq) : [];
    send({ type: 'attached', sessionId: msg.sessionId, replayed: replay.length });
    for (const event of replay) send(event);
  } else if (msg.type === 'cancel') {
    recordInbound(msg);
    const run = state.runs[msg.runId];
    if (!run) return;
    if (run.cancelLost) {
      // The cancel is lost with the process; storage keeps the run RUNNING.
      run.cancelLost = false;
      save();
      process.exit(2);
    }
    if (run.status === 'RUNNING') finish(run, 'CANCELLED');
  }
});
lines.on('close', () => process.exit(0));
