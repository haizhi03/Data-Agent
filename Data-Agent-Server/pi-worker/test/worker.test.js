/**
 * Layer-1 verification: drive the real worker process over stdio with a
 * scripted OpenAI-compatible fixture server. No real model, no real database,
 * no vendor cost.
 */
import { spawn } from 'node:child_process';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import url from 'node:url';
import { startFixtureServer, textResponse, toolCallResponse } from './fixture-openai-server.js';

const WORKER = path.join(path.dirname(url.fileURLToPath(import.meta.url)), '..', 'src', 'worker.js');
const workers = [];
after(() => {
  for (const worker of workers.splice(0)) worker.process.kill('SIGKILL');
});

function startWorker() {
  const child = spawn(process.execPath, [WORKER], { stdio: ['pipe', 'pipe', 'pipe'] });
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
    get stderr() {
      return stderr;
    },
    send: (message) => child.stdin.write(JSON.stringify(message) + '\n'),
    waitFor: (predicate, timeoutMs = 15000) =>
      new Promise((resolve, reject) => {
        const found = messages.find(predicate);
        if (found) return resolve(found);
        const timer = setTimeout(() => reject(new Error(`timed out waiting for message; saw ${JSON.stringify(messages)}`)), timeoutMs);
        waiters.push({
          predicate,
          resolve: (message) => {
            clearTimeout(timer);
            resolve(message);
          },
        });
      }),
    exited: new Promise((resolve) => child.on('exit', (code) => resolve(code))),
  };
  workers.push(worker);
  return worker;
}

function startMessage(overrides = {}) {
  return {
    type: 'start',
    runId: 'run-1',
    sessionId: 'session-1',
    systemPrompt: 'You are a local database assistant for tests.',
    message: 'hello',
    apiKey: 'fixture-key',
    model: { apiStyle: 'OPENAI_CHAT_COMPLETIONS', baseUrl: overrides.baseUrl, modelId: 'fixture-model' },
    ...overrides,
  };
}

async function waitHello(worker) {
  const hello = await worker.waitFor((m) => m.type === 'hello');
  assert.equal(hello.protocol, 1);
}

test('simple text run streams deltas and finishes SUCCEEDED', async () => {
  const server = await startFixtureServer(() => textResponse('你好，世界'));
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED');
    assert.equal(finished.runId, 'run-1');
    assert.equal(finished.usage.modelCalls, 1);
    assert.equal(finished.usage.toolCalls, 0);
    assert.ok(finished.usage.inputTokens > 0);
    const text = worker.messages.filter((m) => m.type === 'text_delta').map((m) => m.delta).join('');
    assert.equal(text, '你好，世界');
    assert.equal(server.calls.length, 1);
    const last = server.calls[0].messages.at(-1);
    assert.equal(last.role, 'user');
    assert.equal(typeof last.content === 'string' ? last.content : last.content.map((b) => b.text).join(''), 'hello');
  } finally {
    await server.close();
  }
});

test('tool call round trip: tool_call out, tool_result in, model continues, then finishes', async () => {
  const server = await startFixtureServer((body, count) => {
    if (count === 1) return toolCallResponse([{ id: 'call_1', name: 'list_tables', args: {} }]);
    // Second call must see the tool result in the transcript.
    const toolMessage = body.messages.find((m) => m.role === 'tool');
    assert.ok(toolMessage, 'tool result message must be present');
    assert.ok(toolMessage.content.includes('orders'));
    return textResponse('找到了 orders 表');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl, message: '有哪些表' }));
    const toolCall = await worker.waitFor((m) => m.type === 'tool_call');
    assert.equal(toolCall.runId, 'run-1');
    assert.equal(toolCall.toolCallId, 'call_1');
    assert.equal(toolCall.name, 'list_tables');
    assert.deepEqual(JSON.parse(toolCall.arguments), {});
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_1', isError: false,
      result: JSON.stringify({ tables: ['orders', 'users'], truncated: false }) });
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED');
    assert.equal(finished.usage.modelCalls, 2);
    assert.equal(finished.usage.toolCalls, 1);
    const text = worker.messages.filter((m) => m.type === 'text_delta').map((m) => m.delta).join('');
    assert.equal(text, '找到了 orders 表');
  } finally {
    await server.close();
  }
});

test('tool failure reaches the model as an error and the run still completes', async () => {
  const server = await startFixtureServer((body, count) => {
    if (count === 1) return toolCallResponse([{ id: 'call_1', name: 'describe_table', args: { tableName: 'orders' } }]);
    const toolMessage = body.messages.find((m) => m.role === 'tool');
    assert.ok(toolMessage);
    return textResponse('读取失败：CONNECTION_NOT_FOUND');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl, message: '看看 orders 结构' }));
    await worker.waitFor((m) => m.type === 'tool_call');
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_1', isError: true, result: 'CONNECTION_NOT_FOUND' });
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED');
    assert.ok(worker.messages.filter((m) => m.type === 'text_delta').map((m) => m.delta).join('').includes('读取失败'));
  } finally {
    await server.close();
  }
});

test('cancel while the model is streaming ends the run CANCELLED', async () => {
  const server = await startFixtureServer(() => textResponse('x'.repeat(6000), { delayMs: 2 }));
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    await worker.waitFor((m) => m.type === 'text_delta');
    worker.send({ type: 'cancel', runId: 'run-1' });
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'CANCELLED');
  } finally {
    await server.close();
  }
});

test('cancel while waiting for a tool result ends the run CANCELLED', async () => {
  const server = await startFixtureServer(() => toolCallResponse([{ id: 'call_1', name: 'list_tables', args: {} }]));
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    await worker.waitFor((m) => m.type === 'tool_call');
    worker.send({ type: 'cancel', runId: 'run-1' });
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'CANCELLED');
  } finally {
    await server.close();
  }
});

test('one active run per session; a second start is rejected without touching the first run', async () => {
  const server = await startFixtureServer((body, count) =>
    count === 1 ? toolCallResponse([{ id: 'call_1', name: 'list_tables', args: {} }]) : textResponse('done'));
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    await worker.waitFor((m) => m.type === 'tool_call');
    worker.send(startMessage({ baseUrl: server.baseUrl, runId: 'run-2', message: 'another' }));
    const rejected = await worker.waitFor((m) => m.type === 'finished' && m.runId === 'run-2');
    assert.equal(rejected.status, 'FAILED');
    assert.equal(rejected.errorCode, 'PI_SESSION_BUSY');
    worker.send({ type: 'cancel', runId: 'run-1' });
    const first = await worker.waitFor((m) => m.type === 'finished' && m.runId === 'run-1');
    assert.equal(first.status, 'CANCELLED');
    assert.equal(server.calls.length, 1, 'rejected run must not call the model');
  } finally {
    await server.close();
  }
});

test('multi-turn: a second start on the same session keeps the in-memory transcript', async () => {
  const server = await startFixtureServer((body, count) => {
    if (count === 1) return textResponse('第一轮回答');
    const textOf = (m) => (typeof m.content === 'string' ? m.content : m.content.map((b) => b.text).join(''));
    const userTexts = body.messages.filter((m) => m.role === 'user').map(textOf);
    assert.deepEqual(userTexts, ['第一轮问题', '第二轮问题']);
    assert.ok(body.messages.some((m) => m.role === 'assistant' && String(m.content).includes('第一轮回答')));
    return textResponse('第二轮回答');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl, message: '第一轮问题' }));
    const first = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(first.status, 'SUCCEEDED');
    worker.send(startMessage({ baseUrl: server.baseUrl, runId: 'run-2', message: '第二轮问题' }));
    const second = await worker.waitFor((m) => m.type === 'finished' && m.runId === 'run-2');
    assert.equal(second.status, 'SUCCEEDED');
    assert.equal(server.calls.length, 2);
  } finally {
    await server.close();
  }
});

test('unsupported apiStyle fails clearly without calling any endpoint', async () => {
  const worker = startWorker();
  await waitHello(worker);
  worker.send(startMessage({ baseUrl: 'http://127.0.0.1:1/v1', model: { apiStyle: 'DASHSCOPE_CHAT', baseUrl: 'http://x', modelId: 'm' } }));
  const finished = await worker.waitFor((m) => m.type === 'finished');
  assert.equal(finished.status, 'FAILED');
  assert.equal(finished.errorCode, 'PI_UNSUPPORTED_API_STYLE');
});

test('provider HTTP error maps to FAILED PI_MODEL_ERROR', async () => {
  const server = await startFixtureServer(() => ({ status: 401, json: { error: { message: 'bad key' } } }));
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const finished = await worker.waitFor((m) => m.type === 'finished', 30000);
    assert.equal(finished.status, 'FAILED');
    assert.equal(finished.errorCode, 'PI_MODEL_ERROR');
    // Credentials must not leak to stdout protocol messages.
    assert.ok(!JSON.stringify(worker.messages).includes('fixture-key'));
  } finally {
    await server.close();
  }
});

test('late tool_result after a run finished is dropped', async () => {
  const server = await startFixtureServer(() => textResponse('ok'));
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED');
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_missing', isError: false, result: '{}' });
    await new Promise((r) => setTimeout(r, 300));
    assert.equal(worker.messages.filter((m) => m.type === 'finished').length, 1, 'exactly one terminal message');
  } finally {
    await server.close();
  }
});

test('malformed and oversized inbound lines produce protocol errors, not a crash', async () => {
  const server = await startFixtureServer(() => textResponse('ok'));
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.process.stdin.write('not-json\n');
    const protocolError = await worker.waitFor((m) => m.type === 'error' && m.errorCode === 'PI_PROTOCOL_ERROR');
    assert.ok(protocolError);
    worker.process.stdin.write(JSON.stringify(startMessage({ baseUrl: server.baseUrl })) + '\n');
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED', 'worker keeps working after a bad line');
  } finally {
    await server.close();
  }
});

test('stdin EOF shuts the worker down', async () => {
  const worker = startWorker();
  await waitHello(worker);
  worker.process.stdin.end();
  const code = await worker.exited;
  assert.equal(code, 0);
});
