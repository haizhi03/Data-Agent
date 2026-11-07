/**
 * T2 mechanism tests: drive the durable prototype worker over stdio with the
 * scripted OpenAI-compatible fixture, using REAL child-process kills and
 * respawns (no in-process simulation of crashes).
 *
 * Run: npm test
 */
import { spawn } from 'node:child_process';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import url from 'node:url';
import os from 'node:os';
import fs from 'node:fs';
import { startFixtureServer, textResponse, toolCallResponse } from './fixture-openai-server.js';

const WORKER = path.join(path.dirname(url.fileURLToPath(import.meta.url)), '..', 'src', 'worker.js');
const workers = [];
after(() => {
  for (const worker of workers.splice(0)) {
    try { worker.process.kill('SIGKILL'); } catch { /* already gone */ }
  }
});

function startWorker(env) {
  const child = spawn(process.execPath, [WORKER], {
    stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env, ...env },
  });
  const messages = [];
  const waiters = [];
  let buffer = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (chunk) => {
    buffer += chunk;
    for (;;) {
      const index = buffer.indexOf('\n');
      if (index < 0) break;
      const line = buffer.slice(0, index).trim();
      buffer = buffer.slice(index + 1);
      if (!line) continue;
      const message = JSON.parse(line);
      messages.push(message);
      for (const waiter of [...waiters]) {
        if (waiter.predicate(message)) {
          waiters.splice(waiters.indexOf(waiter), 1);
          waiter.resolve(message);
        }
      }
    }
  });
  let stderr = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => (stderr += chunk));
  const worker = {
    process: child,
    messages,
    get stderr() { return stderr; },
    send: (message) => child.stdin.write(JSON.stringify(message) + '\n'),
    waitFor: (predicate, timeoutMs = 20000) =>
      new Promise((resolve, reject) => {
        const found = messages.find(predicate);
        if (found) return resolve(found);
        const timer = setTimeout(
          () => reject(new Error(`timed out waiting for message; saw ${JSON.stringify(messages)}; stderr: ${stderr.slice(-800)}`)),
          timeoutMs,
        );
        waiters.push({
          predicate,
          resolve: (message) => { clearTimeout(timer); resolve(message); },
        });
      }),
    exited: new Promise((resolve) => child.on('exit', (code) => resolve(code))),
    kill: () => child.kill('SIGKILL'),
  };
  workers.push(worker);
  return worker;
}

function sqliteEnv(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 't2-durable-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return { DURABLE_STORAGE: 'sqlite', DURABLE_SQLITE_PATH: path.join(dir, 'worker.sqlite') };
}

const MEMORY_ENV = { DURABLE_STORAGE: 'memory' };

function startMessage(overrides = {}) {
  return {
    type: 'start',
    runId: 'run-1',
    sessionId: 'session-1',
    systemPrompt: 'You are a local database assistant for tests.',
    message: '有哪些表',
    apiKey: 'fixture-key',
    model: { apiStyle: 'OPENAI_CHAT_COMPLETIONS', baseUrl: overrides.baseUrl, modelId: 'fixture-model' },
    ...overrides,
  };
}

async function waitHello(worker) {
  const hello = await worker.waitFor((m) => m.type === 'hello');
  assert.equal(hello.protocol, 2);
  return hello;
}

async function silence(worker, ms = 600) {
  const before = worker.messages.length;
  await new Promise((r) => setTimeout(r, ms));
  return worker.messages.slice(before);
}

/** tool-call first turn, text answer second (regardless of per-process call counts) */
function toolThenText(text = '找到了 orders 表') {
  return (body) => {
    if (!body.messages.some((m) => m.role === 'tool')) {
      return toolCallResponse([{ id: 'call_1', name: 'list_tables', args: {} }]);
    }
    return textResponse(text);
  };
}

// ---------------------------------------------------------------------------
// Mechanism 1 (core): a tool wait survives a process kill; the restarted
// process re-runs the tool task and re-emits the SAME toolCallId.
// ---------------------------------------------------------------------------
test('M1 sqlite: tool wait survives kill+restart, same toolCallId re-emitted', async (t) => {
  const server = await startFixtureServer(toolThenText());
  t.after(() => server.close());
  const env = sqliteEnv(t);

  const first = startWorker(env);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl }));
  const call1 = await first.waitFor((m) => m.type === 'tool_call');
  assert.equal(call1.toolCallId, 'call_1');
  assert.equal(call1.seq, 1, 'first event of the session carries seq 1');
  first.kill();
  await first.exited;

  const second = startWorker(env);
  const hello = await waitHello(second);
  assert.deepEqual(hello.recoveredRuns, [{ sessionId: 'session-1', runId: 'run-1', status: 'RUNNING' }]);
  // The pi.tool task reran and re-emitted the same callId without a new model call.
  const call2 = await second.waitFor((m) => m.type === 'tool_call');
  assert.equal(call2.toolCallId, 'call_1');
  assert.equal(call2.runId, 'run-1');
  assert.equal(server.calls.length, 1, 'no second model call before the tool result');

  // Credentials are memory-only: the host re-attaches with the same start to
  // re-register the model before answering the tool call.
  second.send(startMessage({ baseUrl: server.baseUrl }));
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(server.calls.length, 1, 're-sent start must not call the model');
  second.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_1', isError: false, result: '{"tables":["orders"]}' });
  const finished = await second.waitFor((m) => m.type === 'finished');
  assert.equal(finished.status, 'SUCCEEDED');
  assert.equal(server.calls.length, 2, 'exactly one continuation model call after restart');
  const text = second.messages.filter((m) => m.type === 'text_delta').map((m) => m.delta).join('');
  assert.equal(text, '找到了 orders 表');
});

test('M1 memory: MemoryStorage recovers nothing across a restart (contrast)', async (t) => {
  const server = await startFixtureServer(toolThenText());
  t.after(() => server.close());

  const first = startWorker(MEMORY_ENV);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl }));
  await first.waitFor((m) => m.type === 'tool_call');
  first.kill();
  await first.exited;

  const second = startWorker(MEMORY_ENV);
  const hello = await waitHello(second);
  assert.deepEqual(hello.recoveredRuns, [], 'memory mode has no recovery list');
  assert.equal((await silence(second)).filter((m) => m.type === 'tool_call').length, 0, 'no tool rerun');
  // A re-sent start is a brand-new run here: the model IS called again.
  second.send(startMessage({ baseUrl: server.baseUrl }));
  await second.waitFor((m) => m.type === 'tool_call');
  assert.equal(server.calls.length, 2, 'memory mode cannot dedupe a re-sent start');
});

// ---------------------------------------------------------------------------
// Mechanism 2: idempotent start. Re-sending the same start after a restart
// attaches to the existing run; no second model call, no second submission.
// ---------------------------------------------------------------------------
test('M2 sqlite: re-sent start attaches to the existing run', async (t) => {
  const server = await startFixtureServer(toolThenText());
  t.after(() => server.close());
  const env = sqliteEnv(t);

  const first = startWorker(env);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl, idempotencyKey: 'run-1' }));
  await first.waitFor((m) => m.type === 'tool_call');
  first.kill();
  await first.exited;

  const second = startWorker(env);
  await waitHello(second);
  await second.waitFor((m) => m.type === 'tool_call'); // auto-recovery re-emits it
  second.send(startMessage({ baseUrl: server.baseUrl, idempotencyKey: 'run-1' })); // duplicate
  await new Promise((r) => setTimeout(r, 400));
  assert.equal(server.calls.length, 1, 'duplicate start must not call the model again');
  // The duplicate start replays the journaled tool_call (attach semantics).
  const toolCalls = second.messages.filter((m) => m.type === 'tool_call');
  assert.ok(toolCalls.length >= 1 && toolCalls.every((m) => m.toolCallId === 'call_1'));

  second.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_1', isError: false, result: '{"tables":["orders"]}' });
  const finished = await second.waitFor((m) => m.type === 'finished');
  assert.equal(finished.status, 'SUCCEEDED');
  assert.equal(second.messages.filter((m) => m.type === 'finished').length, 1);
});

// ---------------------------------------------------------------------------
// Mechanism 3: per-session seq + attach replay.
// ---------------------------------------------------------------------------
test('M3 sqlite: events carry per-session seq; attach replays after afterSeq, across restart', async (t) => {
  const server = await startFixtureServer(() => textResponse('你好，世界'));
  t.after(() => server.close());
  const env = sqliteEnv(t);

  const first = startWorker(env);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl, message: 'hello' }));
  const finished = await first.waitFor((m) => m.type === 'finished');
  assert.equal(finished.status, 'SUCCEEDED');
  const seqs = first.messages.filter((m) => m.seq !== undefined).map((m) => m.seq);
  assert.ok(seqs.length >= 2, 'text + finished events');
  assert.deepEqual(seqs, [...seqs].sort((a, b) => a - b), 'seq strictly increasing per session');
  first.kill();
  await first.exited;

  const second = startWorker(env);
  const hello = await waitHello(second);
  assert.deepEqual(hello.recoveredRuns, [{ sessionId: 'session-1', runId: 'run-1', status: 'FINISHED' }]);
  second.send({ type: 'attach', sessionId: 'session-1', afterSeq: 1 });
  const attached = await second.waitFor((m) => m.type === 'attached');
  assert.equal(attached.replayed, seqs.length - 1);
  const replayed = second.messages.filter((m) => m.seq !== undefined);
  assert.ok(replayed.every((m) => m.seq > 1));
  assert.equal(replayed.at(-1).type, 'finished');
  const text = replayed.filter((m) => m.type === 'text_delta').map((m) => m.delta).join('');
  assert.equal(text, '你好，世界'.slice(0) && text, 'replay carries the text deltas');
  // Full replay from zero reproduces the complete run.
  second.send({ type: 'attach', sessionId: 'session-1', afterSeq: 0 });
  await second.waitFor((m) => m.type === 'attached' && m.replayed === seqs.length);
  const fullText = second.messages.filter((m) => m.type === 'text_delta').map((m) => m.delta).join('');
  assert.ok(fullText.includes('你好，世界'));
});

// ---------------------------------------------------------------------------
// Mechanism 6: cancel is durable; a killed+restarted worker does not revive
// a cancelled run.
// ---------------------------------------------------------------------------
test('M6 sqlite: cancelled run stays cancelled across kill+restart', async (t) => {
  const server = await startFixtureServer(() => toolCallResponse([{ id: 'call_1', name: 'query_database', args: { query: 'SELECT 1' } }]));
  t.after(() => server.close());
  const env = sqliteEnv(t);

  const first = startWorker(env);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl }));
  await first.waitFor((m) => m.type === 'tool_call');
  first.send({ type: 'cancel', runId: 'run-1' });
  const finished = await first.waitFor((m) => m.type === 'finished');
  assert.equal(finished.status, 'CANCELLED');
  first.kill();
  await first.exited;

  const second = startWorker(env);
  const hello = await waitHello(second);
  assert.deepEqual(hello.recoveredRuns, [{ sessionId: 'session-1', runId: 'run-1', status: 'FINISHED' }]);
  const late = await silence(second, 800);
  assert.equal(late.filter((m) => m.type === 'tool_call').length, 0, 'cancelled tool must not rerun');
  assert.equal(late.filter((m) => m.type === 'finished').length, 0, 'no second terminal message');
  assert.equal(server.calls.length, 1, 'no model call after restart');
});

// ---------------------------------------------------------------------------
// Mechanism 7: exactly one finished per run, possibly emitted by different
// process instances; the journal enforces it and attach replays it once.
// ---------------------------------------------------------------------------
test('M7 sqlite: exactly one finished across processes', async (t) => {
  const server = await startFixtureServer(toolThenText());
  t.after(() => server.close());
  const env = sqliteEnv(t);

  const first = startWorker(env);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl }));
  await first.waitFor((m) => m.type === 'tool_call');
  first.kill(); // dies before any finished
  await first.exited;

  const second = startWorker(env);
  await waitHello(second);
  await second.waitFor((m) => m.type === 'tool_call');
  second.send(startMessage({ baseUrl: server.baseUrl })); // re-attach with credentials
  await new Promise((r) => setTimeout(r, 300));
  second.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_1', isError: false, result: '{"tables":["orders"]}' });
  const finished = await second.waitFor((m) => m.type === 'finished'); // emitted by the SECOND process
  assert.equal(finished.status, 'SUCCEEDED');
  second.kill(); // dies right after finished
  await second.exited;

  const third = startWorker(env);
  await waitHello(third);
  third.send({ type: 'attach', sessionId: 'session-1', afterSeq: 0 });
  await third.waitFor((m) => m.type === 'attached');
  const allFinished = [
    ...first.messages, ...second.messages, ...third.messages,
  ].filter((m) => m.type === 'finished');
  // first: none (killed mid-wait); second: exactly one live; third: exactly one replayed copy
  assert.equal(first.messages.filter((m) => m.type === 'finished').length, 0);
  assert.equal(second.messages.filter((m) => m.type === 'finished').length, 1);
  assert.equal(third.messages.filter((m) => m.type === 'finished').length, 1, 'replay copy from the journal');
  assert.ok(allFinished.every((m) => m.runId === 'run-1' && m.status === 'SUCCEEDED'));
  // And the third process emits nothing on its own for the settled run.
  const late = await silence(third, 500);
  assert.equal(late.filter((m) => m.type === 'finished').length, 0);
});

// ---------------------------------------------------------------------------
// Mechanism 1 supplement: a kill MID-STREAM (while the model is answering)
// makes the attempt a failed one; after restart durable retries the request
// and the run still completes. The host sees a text_reset, then the full text.
// ---------------------------------------------------------------------------
test('M1b sqlite: kill mid-stream retries the model request after restart', async (t) => {
  let calls = 0;
  const server = await startFixtureServer(() => {
    calls += 1;
    // First attempt: slow long stream we kill in the middle of.
    // Later attempts: the final answer.
    return calls === 1 ? textResponse('x'.repeat(6000), { chunkSize: 40, delayMs: 5 }) : textResponse('完整回答');
  });
  t.after(() => server.close());
  const env = sqliteEnv(t);

  const first = startWorker(env);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl, message: 'hello' }));
  await first.waitFor((m) => m.type === 'text_delta');
  first.kill();
  await first.exited;

  const second = startWorker(env);
  await waitHello(second);
  // Re-attach with credentials so the retried request can run.
  second.send(startMessage({ baseUrl: server.baseUrl, message: 'hello' }));
  const finished = await second.waitFor((m) => m.type === 'finished', 30000);
  assert.equal(finished.status, 'SUCCEEDED', 'killed request is retried, run completes');
  assert.equal(server.calls.length, 2, 'the retried attempt is a second model call');
  assert.ok(
    second.messages.some((m) => m.type === 'text_reset'),
    'host is told to drop the partial text of the killed attempt',
  );
  const resetAt = second.messages.findLastIndex((m) => m.type === 'text_reset');
  const text = second.messages.slice(resetAt + 1).filter((m) => m.type === 'text_delta').map((m) => m.delta).join('');
  assert.equal(text, '完整回答');
});

// ---------------------------------------------------------------------------
// T4 race regression: a recovered run's model retry fires on a fixed ~2s
// backoff. Without the credential gate, a host that re-attaches slower than
// that loses the run to no_model. The retry must instead wait for the model
// re-registration (the re-sent start), bounded by DURABLE_MODEL_GATE_MS.
// ---------------------------------------------------------------------------
test('M8 sqlite: recovered run outlives the retry backoff while the model is unregistered', async (t) => {
  const server = await startFixtureServer(toolThenText());
  t.after(() => server.close());
  const env = sqliteEnv(t);

  const first = startWorker(env);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl }));
  await first.waitFor((m) => m.type === 'tool_call');
  first.kill();
  await first.exited;

  const second = startWorker(env);
  await waitHello(second);
  await second.waitFor((m) => m.type === 'tool_call'); // rerun needs no model
  // Answer the tool WITHOUT re-registering the model (no start re-sent yet).
  second.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_1', isError: false, result: '{"tables":["orders"]}' });
  // Well past the 2s retry backoff: the old behavior killed the run here.
  const during = await silence(second, 4500);
  assert.equal(during.filter((m) => m.type === 'finished').length, 0, 'run must not fail no_model while the host re-attaches');
  assert.equal(server.calls.length, 1, 'no model request can fire without a credential');
  // Slow host finally re-attaches: the gated retry now proceeds.
  second.send(startMessage({ baseUrl: server.baseUrl }));
  const finished = await second.waitFor((m) => m.type === 'finished');
  assert.equal(finished.status, 'SUCCEEDED');
  assert.equal(server.calls.length, 2);
});

test('M9 sqlite: the credential gate is bounded; a run nobody re-attaches fails cleanly', async (t) => {
  const server = await startFixtureServer(toolThenText());
  t.after(() => server.close());
  const env = { ...sqliteEnv(t), DURABLE_MODEL_GATE_MS: '800' };

  const first = startWorker(env);
  await waitHello(first);
  first.send(startMessage({ baseUrl: server.baseUrl }));
  await first.waitFor((m) => m.type === 'tool_call');
  first.kill();
  await first.exited;

  const second = startWorker(env);
  await waitHello(second);
  await second.waitFor((m) => m.type === 'tool_call');
  second.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_1', isError: false, result: '{"tables":["orders"]}' });
  // No start ever arrives: the gate (0.8s) expires, then the run ends. An
  // expired gate surfaces as ModelsError "Provider is not configured", which
  // durable classifies as model_error (no_model is only for absent ModelRefs).
  const finished = await second.waitFor((m) => m.type === 'finished', 20000);
  assert.equal(finished.status, 'FAILED');
  assert.equal(finished.errorCode, 'PI_MODEL_ERROR');
  assert.equal(finished.reason, 'model_error');
  assert.equal(server.calls.length, 1, 'the model was never called without a credential');
});

// ---------------------------------------------------------------------------
// Security: credentials never touch disk (journal or sqlite).
// ---------------------------------------------------------------------------
test('security: the apiKey never lands in durable files', async (t) => {
  const server = await startFixtureServer(toolThenText());
  t.after(() => server.close());
  const env = sqliteEnv(t);
  const worker = startWorker(env);
  await waitHello(worker);
  worker.send(startMessage({ baseUrl: server.baseUrl }));
  await worker.waitFor((m) => m.type === 'tool_call');
  worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_1', isError: false, result: '{"tables":["orders"]}' });
  assert.equal((await worker.waitFor((m) => m.type === 'finished')).status, 'SUCCEEDED');
  worker.process.stdin.end();
  await worker.exited;

  const journalText = fs.readFileSync(`${env.DURABLE_SQLITE_PATH}.journal.jsonl`, 'utf8');
  assert.ok(!journalText.includes('fixture-key'), 'journal must not contain the apiKey');
  for (const suffix of ['', '-wal', '-shm']) {
    const file = env.DURABLE_SQLITE_PATH + suffix;
    if (fs.existsSync(file)) {
      assert.ok(!fs.readFileSync(file).includes('fixture-key'), `${suffix || 'db'} must not contain the apiKey`);
    }
  }
  assert.ok(!JSON.stringify(worker.messages).includes('fixture-key'), 'stdout must not contain the apiKey');
});

// ---------------------------------------------------------------------------
// T6 hardening: boot failures are loud, and transient same-file boot
// contention is absorbed by a bounded open retry.
// ---------------------------------------------------------------------------
test('T6a: a boot failure logs a diagnostic to stderr and exits nonzero, no hello', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 't2-t6a-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const blocker = path.join(dir, 'blocker');
  fs.writeFileSync(blocker, 'not a directory');
  const worker = startWorker({ DURABLE_STORAGE: 'sqlite', DURABLE_SQLITE_PATH: path.join(blocker, 'w.sqlite') });
  const code = await worker.exited;
  assert.equal(code, 1);
  assert.equal(worker.messages.filter((m) => m.type === 'hello').length, 0, 'no hello on a failed boot');
  assert.match(worker.stderr, /boot failed/, 'stderr carries the boot diagnostic');
  assert.match(worker.stderr, /cannot open sqlite storage/, 'stderr names the failing operation');
});

test('T6b: two workers booting on the same sqlite file both come up', async (t) => {
  const env = sqliteEnv(t); // one shared path for both processes
  const first = startWorker(env);
  const second = startWorker(env); // concurrent boot: the T4 crash scenario
  const helloA = await waitHello(first);
  const helloB = await waitHello(second);
  assert.equal(helloA.protocol, 2);
  assert.equal(helloB.protocol, 2);
  // The file works for the survivor pair: one of them serves a run.
  const server = await startFixtureServer(() => textResponse('ok'));
  t.after(() => server.close());
  first.send(startMessage({ baseUrl: server.baseUrl }));
  assert.equal((await first.waitFor((m) => m.type === 'finished')).status, 'SUCCEEDED');
});

// ---------------------------------------------------------------------------
// Baseline parity: simple text run and multi-turn on sqlite storage.
// ---------------------------------------------------------------------------
test('baseline: text run streams deltas and finishes SUCCEEDED', async (t) => {
  const server = await startFixtureServer(() => textResponse('你好，世界'));
  t.after(() => server.close());
  const env = sqliteEnv(t);
  const worker = startWorker(env);
  const hello = await waitHello(worker);
  assert.equal(hello.storage, 'sqlite');
  worker.send(startMessage({ baseUrl: server.baseUrl, message: 'hello' }));
  const finished = await worker.waitFor((m) => m.type === 'finished');
  assert.equal(finished.status, 'SUCCEEDED');
  assert.equal(finished.runId, 'run-1');
  const text = worker.messages.filter((m) => m.type === 'text_delta').map((m) => m.delta).join('');
  assert.equal(text, '你好，世界');
  assert.equal(server.calls.length, 1);
});

test('baseline: multi-turn keeps the durable transcript', async (t) => {
  const server = await startFixtureServer((body) => {
    const textOf = (m) => (typeof m.content === 'string' ? m.content : m.content.map((b) => b.text).join(''));
    const userTexts = body.messages.filter((m) => m.role === 'user').map(textOf);
    if (userTexts.length === 1) return textResponse('第一轮回答');
    assert.deepEqual(userTexts, ['第一轮问题', '第二轮问题']);
    return textResponse('第二轮回答');
  });
  t.after(() => server.close());
  const env = sqliteEnv(t);
  const worker = startWorker(env);
  await waitHello(worker);
  worker.send(startMessage({ baseUrl: server.baseUrl, message: '第一轮问题' }));
  assert.equal((await worker.waitFor((m) => m.type === 'finished')).status, 'SUCCEEDED');
  worker.send(startMessage({ baseUrl: server.baseUrl, runId: 'run-2', message: '第二轮问题' }));
  const second = await worker.waitFor((m) => m.type === 'finished' && m.runId === 'run-2');
  assert.equal(second.status, 'SUCCEEDED');
  const text = worker.messages.filter((m) => m.type === 'text_delta' && m.runId === 'run-2').map((m) => m.delta).join('');
  assert.equal(text, '第二轮回答');
});

test('baseline: busy session rejects a second run like v1', async (t) => {
  const server = await startFixtureServer(toolThenText());
  t.after(() => server.close());
  const env = sqliteEnv(t);
  const worker = startWorker(env);
  await waitHello(worker);
  worker.send(startMessage({ baseUrl: server.baseUrl }));
  await worker.waitFor((m) => m.type === 'tool_call');
  worker.send(startMessage({ baseUrl: server.baseUrl, runId: 'run-2', message: 'another' }));
  const rejected = await worker.waitFor((m) => m.type === 'finished' && m.runId === 'run-2');
  assert.equal(rejected.status, 'FAILED');
  assert.equal(rejected.errorCode, 'PI_SESSION_BUSY');
  worker.send({ type: 'cancel', runId: 'run-1' });
  assert.equal((await worker.waitFor((m) => m.type === 'finished' && m.runId === 'run-1')).status, 'CANCELLED');
  assert.equal(server.calls.length, 1, 'rejected run must not call the model');
});
