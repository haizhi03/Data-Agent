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
import { startFixtureServer, textResponse, toolCallResponse, chunk, usageChunk } from './fixture-openai-server.js';

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

test('an unscoped assistant discovers data sources and namespaces before listing tables', async () => {
  const server = await startFixtureServer((body, count) => {
    assert.ok(body.messages.some(message => message.role === 'system' && message.content.includes('You are a local database assistant for tests.')),
      'The configured system prompt must reach the model on every call');
    const names = body.tools.map(tool => tool.function.name);
    assert.ok(names.includes('list_connections')); assert.ok(names.includes('list_namespaces'));
    if (count === 1) return toolCallResponse([{ id: 'sources', name: 'list_connections', args: {} }]);
    const result = JSON.parse(body.messages.filter(message => message.role === 'tool').at(-1).content);
    if (count === 2) {
      assert.equal(result.connections[0].name, 'Demo');
      return toolCallResponse([{ id: 'namespaces', name: 'list_namespaces', args: { connectionId: result.connections[0].connectionId } }]);
    }
    if (count === 3) {
      assert.deepEqual(result.catalogs, ['sales']);
      return toolCallResponse([{ id: 'tables', name: 'list_tables', args: { connectionId: 7, catalogName: result.catalogs[0] } }]);
    }
    assert.deepEqual(result.tables, ['orders', 'users']);
    return textResponse('Demo 的 sales 数据库有2张表');
  });
  try {
    const worker = startWorker(); await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl, message: '当前数据库有多少表？', context: { defaultTarget: {} } }));
    for (const [id, name, args, result] of [
      ['sources', 'list_connections', {}, { connections: [{ connectionId: 7, name: 'Demo', dbType: 'mysql' }], truncated: false }],
      ['namespaces', 'list_namespaces', { connectionId: 7 }, { catalogs: ['sales'], schemas: [], supportsCatalog: true, supportsSchema: false, truncated: false }],
      ['tables', 'list_tables', { connectionId: 7, catalogName: 'sales' }, { tables: ['orders', 'users'], truncated: false }],
    ]) {
      const call = await worker.waitFor(message => message.type === 'tool_call' && message.toolCallId === id);
      assert.equal(call.name, name); assert.deepEqual(JSON.parse(call.arguments), args);
      worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: id, result: JSON.stringify(result) });
    }
    const done = await worker.waitFor(message => message.type === 'finished');
    assert.equal(done.status, 'SUCCEEDED');
    assert.match(worker.messages.filter(message => message.type === 'text_delta').map(message => message.delta).join(''), /有2张表/);
  } finally { await server.close(); }
});

test('assistant delegates Explorer tasks and resumes with the structured task result', async () => {
  const tasks = [{ connectionId: 7, catalogName: 'sales', instruction: 'Find orders' }];
  const server = await startFixtureServer((body, count) => {
    assert.ok(body.tools.some(tool => tool.function.name === 'callingExplorerSubAgent'));
    if (count === 1) return toolCallResponse([{ id: 'delegate', name: 'callingExplorerSubAgent', args: { tasks } }]);
    assert.match(body.messages.filter(message => message.role === 'tool').at(-1).content, /verified orders/);
    return textResponse('Explorer found orders');
  });
  try {
    const worker = startWorker(); await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const call = await worker.waitFor(message => message.type === 'tool_call');
    assert.equal(call.name, 'callingExplorerSubAgent'); assert.deepEqual(JSON.parse(call.arguments), { tasks });
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'delegate', isError: false,
      result: JSON.stringify({ taskResults: [{ taskId: 'task-1', status: 'SUCCESS', summaryText: 'verified orders', objects: [] }] }) });
    assert.equal((await worker.waitFor(message => message.type === 'finished')).status, 'SUCCEEDED');
    assert.equal(server.calls.length, 2);
  } finally { await server.close(); }
});

test('schema Explorer reads one verified batch then has no tools for its summary', async () => {
  const server = await startFixtureServer((body, count) => {
    const names = (body.tools ?? []).map(tool => tool.function.name).sort();
    if (count === 1) {
      assert.deepEqual(names, ['describe_tables', 'list_tables']);
      return toolCallResponse([{ id: 'batch', name: 'describe_tables', args: { tableNames: ['users', 'roles'] } }]);
    }
    assert.deepEqual(names, []);
    return textResponse('{"summaryText":"Verified users and roles","objects":[]}');
  });
  try {
    const worker = startWorker(); await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl, role: 'explorer', toolProfile: 'schema' }));
    await worker.waitFor(message => message.type === 'tool_call');
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'batch', isError: false,
      result: JSON.stringify({ tables: [{ table: 'users', columns: [] }, { table: 'roles', columns: [] }], truncated: false }) });
    assert.equal((await worker.waitFor(message => message.type === 'finished')).status, 'SUCCEEDED');
    assert.equal(server.calls.length, 2);
  } finally { await server.close(); }
});

test('schema delegation closes parent exploration and a new turn restores its tools', async () => {
  const server = await startFixtureServer((body, count) => {
    const names = (body.tools ?? []).map(tool => tool.function.name);
    if (count === 1) return toolCallResponse([{ id: 'delegate-schema', name: 'callingExplorerSubAgent',
      args: { tasks: [{ connectionId: 7, instruction: 'Find users', mode: 'schema' }] } }]);
    if (count === 2) { assert.deepEqual(names, []); return textResponse('Schema summary'); }
    assert.ok(names.includes('query_database')); assert.ok(names.includes('callingExplorerSubAgent'));
    return textResponse('Next turn');
  });
  try {
    const worker = startWorker(); await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    await worker.waitFor(message => message.type === 'tool_call');
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'delegate-schema', isError: false,
      result: JSON.stringify({ taskResults: [{ status: 'SUCCESS', structures: [{ table: 'users', columns: [] }] }], truncated: false }) });
    assert.equal((await worker.waitFor(message => message.type === 'finished' && message.runId === 'run-1')).status, 'SUCCEEDED');
    worker.send(startMessage({ baseUrl: server.baseUrl, runId: 'run-2', message: 'Now count users' }));
    assert.equal((await worker.waitFor(message => message.type === 'finished' && message.runId === 'run-2')).status, 'SUCCEEDED');
    assert.equal(server.calls.length, 3);
  } finally { await server.close(); }
});

test('Explorer role has only read tools and a model-call budget stops before another upstream call', async () => {
  const server = await startFixtureServer(body => {
    assert.deepEqual(body.tools.map(tool => tool.function.name).sort(), ['describe_table', 'describe_tables', 'list_tables', 'query_database']);
    return toolCallResponse([{ id: 'read', name: 'list_tables', args: {} }]);
  });
  try {
    const worker = startWorker(); await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl, role: 'explorer', limits: { maxModelCalls: 1, maxToolCalls: 2 } }));
    await worker.waitFor(message => message.type === 'tool_call');
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'read', isError: false, result: '{"tables":["orders"]}' });
    const finished = await worker.waitFor(message => message.type === 'finished');
    assert.equal(finished.status, 'FAILED'); assert.equal(finished.errorCode, 'AGENT_BUDGET_EXCEEDED');
    assert.equal(server.calls.length, 1);
    assert.equal(worker.messages.filter(message => message.type === 'finished').length, 1);
  } finally { await server.close(); }
});

test('rejects unknown worker roles and oversized call budgets without contacting the model', async () => {
  const worker = startWorker(); await waitHello(worker);
  for (const [index, override] of [{ role: 'planner' }, { limits: { maxModelCalls: 100 } }].entries()) {
    worker.send(startMessage({ runId: `invalid-${index}`, ...override }));
    const finished = await worker.waitFor(message => message.type === 'finished' && message.runId === `invalid-${index}`);
    assert.equal(finished.errorCode, 'PI_PROTOCOL_ERROR');
  }
});

test('an Explorer tool budget rejects excess execution and prevents a further model call', async () => {
  const server = await startFixtureServer((_body, count) => toolCallResponse([{ id: `read-${count}`, name: 'list_tables', args: {} }]));
  try {
    const worker = startWorker(); await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl, role: 'explorer', limits: { maxModelCalls: 6, maxToolCalls: 1 } }));
    await worker.waitFor(message => message.type === 'tool_call');
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'read-1', isError: false, result: '{"tables":[]}' });
    const finished = await worker.waitFor(message => message.type === 'finished');
    assert.equal(finished.errorCode, 'AGENT_BUDGET_EXCEEDED');
    assert.equal(worker.messages.filter(message => message.type === 'tool_call').length, 1);
    assert.equal(server.calls.length, 2);
  } finally { await server.close(); }
});

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

test('metadata to read-only query loop preserves target and result facts until the final answer', async () => {
  const target = { connectionId: 7, catalogName: 'sales', schemaName: 'PUBLIC' };
  const server = await startFixtureServer((body, count) => {
    assert.deepEqual(body.tools.map((tool) => tool.function.name).sort(), ['callingExplorerSubAgent', 'describe_table', 'describe_tables', 'list_connections', 'list_namespaces', 'list_tables', 'query_database']);
    if (count === 1) return toolCallResponse([{ id: 'list', name: 'list_tables', args: target }]);
    if (count === 2) return toolCallResponse([{ id: 'describe', name: 'describe_table', args: { ...target, tableName: 'orders' } }]);
    if (count === 3) return toolCallResponse([{ id: 'query', name: 'query_database', args: { ...target, query: 'SELECT COUNT(*) FROM orders' } }]);
    const result = JSON.parse(body.messages.filter((message) => message.role === 'tool').at(-1).content);
    assert.equal(result.executionId, 'execution-fixture');
    assert.deepEqual(result.target, target);
    assert.equal(result.success, true);
    assert.equal(result.truncated, false);
    assert.equal(result.result.rows[0][0], 7);
    return textResponse('根据 execution-fixture 的查询结果，订单总数为7');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl, message: '查询订单总数' }));
    for (const [id, name, result] of [
      ['list', 'list_tables', { target, tables: ['orders'], truncated: false }],
      ['describe', 'describe_table', { target, columns: [{ name: 'id' }], truncated: false }],
      ['query', 'query_database', { target, executionId: 'execution-fixture', success: true, truncated: false, result: { rows: [[7]] } }],
    ]) {
      const call = await worker.waitFor((message) => message.type === 'tool_call' && message.toolCallId === id);
      assert.equal(call.name, name);
      const args = JSON.parse(call.arguments);
      for (const key of Object.keys(target)) assert.equal(args[key], target[key]);
      assert.equal(worker.messages.filter((message) => message.type === 'finished').length, 0);
      worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: id, isError: false, result: JSON.stringify(result) });
    }
    const finished = await worker.waitFor((message) => message.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED');
    assert.equal(finished.usage.modelCalls, 4);
    assert.equal(finished.usage.toolCalls, 3);
    assert.equal(worker.messages.filter((message) => message.type === 'finished').length, 1);
    assert.ok(worker.messages.filter((message) => message.type === 'text_delta').map((message) => message.delta).join('').includes('订单总数为7'));
  } finally { await server.close(); }
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

test('cancel while waiting for a query result ends the run CANCELLED', async () => {
  const server = await startFixtureServer(() => toolCallResponse([{ id: 'call_1', name: 'query_database', args: { query: 'SELECT 1' } }]));
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

// pi-agent-core/pi-ai 1.5.0 moved tool argument validation from TypeBox to
// pi-schema. The tests below pin the new behavior the worker exposes: invalid
// arguments are rejected before any tool_call reaches the Java host, the model
// receives the pi-schema error text, and coerced/extra fields keep their
// host-visible shape.

test('a missing required field is rejected before the host and the model sees the pi-schema error', async () => {
  const server = await startFixtureServer((body, count) => {
    if (count === 1) return toolCallResponse([{ id: 'bad', name: 'describe_table', args: {} }]);
    const toolMessage = body.messages.find((m) => m.role === 'tool');
    assert.ok(toolMessage, 'validation failure must be fed back to the model as a tool result');
    assert.match(toolMessage.content, /Validation failed for tool "describe_table"/);
    assert.match(toolMessage.content, /- tableName: missing/);
    assert.match(toolMessage.content, /Received arguments:/);
    return textResponse('缺少 tableName，请补充');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED');
    assert.equal(worker.messages.filter((m) => m.type === 'tool_call').length, 0, 'invalid arguments never reach the host');
    assert.equal(server.calls.length, 2);
  } finally {
    await server.close();
  }
});

test('enum and boundary violations list every pi-schema error and never reach the host', async () => {
  const server = await startFixtureServer((body, count) => {
    if (count === 1) return toolCallResponse([{ id: 'bad', name: 'callingExplorerSubAgent',
      args: { tasks: [{ connectionId: 7, instruction: 'Find users', mode: 'full', timeoutSeconds: 46 }] } }]);
    const toolMessage = body.messages.find((m) => m.role === 'tool');
    assert.ok(toolMessage);
    assert.match(toolMessage.content, /Validation failed for tool "callingExplorerSubAgent"/);
    assert.match(toolMessage.content, /- tasks\.0\.mode: expected "schema"/);
    assert.match(toolMessage.content, /- tasks\.0\.timeoutSeconds: expected <= 45/);
    return textResponse('参数不合法');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED');
    assert.equal(worker.messages.filter((m) => m.type === 'tool_call').length, 0);
    assert.equal(server.calls.length, 2);
  } finally {
    await server.close();
  }
});

test('argument coercion, dropped optional nulls and kept extra fields preserve the host-facing shape', async () => {
  const server = await startFixtureServer((body, count) => {
    if (count === 1) return toolCallResponse([{ id: 'coerced', name: 'describe_table',
      args: { tableName: 42, connectionId: '7', catalogName: null, unexpected: 'kept' } }]);
    return textResponse('ok');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const call = await worker.waitFor((m) => m.type === 'tool_call');
    assert.equal(call.name, 'describe_table');
    // pi-schema coerces loose types, drops optional nulls, and keeps extra
    // fields: the host sees the same argument shape it saw with 1.4.0.
    assert.deepEqual(JSON.parse(call.arguments), { tableName: '42', connectionId: 7, unexpected: 'kept' });
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'coerced', isError: false, result: '{"columns":[]}' });
    assert.equal((await worker.waitFor((m) => m.type === 'finished')).status, 'SUCCEEDED');
  } finally {
    await server.close();
  }
});

test('tool arguments streamed in chunks are assembled into one complete tool_call', async () => {
  const longNote = 'x'.repeat(3000);
  const query = `SELECT * FROM orders WHERE note = '${longNote}'\nLIMIT 1`;
  const argsJson = JSON.stringify({ query });
  const escapeAt = argsJson.indexOf('\\n');
  assert.ok(escapeAt > 12, 'test setup needs the escape after the first cut');
  // Cut mid-string and between the backslash and the 'n' of the escape sequence.
  const pieces = [
    argsJson.slice(0, 12),
    argsJson.slice(12, escapeAt + 1),
    argsJson.slice(escapeAt + 1, escapeAt + 2000),
    argsJson.slice(escapeAt + 2000),
  ];
  const server = await startFixtureServer((_body, count) => {
    if (count === 1) {
      return async (write) => {
        await write(chunk({ role: 'assistant', tool_calls: [{ index: 0, id: 'call_chunked', type: 'function',
          function: { name: 'query_database', arguments: pieces[0] } }] }));
        for (const piece of pieces.slice(1)) {
          await write(chunk({ tool_calls: [{ index: 0, function: { arguments: piece } }] }));
        }
        await write(chunk({}, 'tool_calls'));
        await write(usageChunk());
      };
    }
    return textResponse('查询完成');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const call = await worker.waitFor((m) => m.type === 'tool_call');
    assert.equal(call.toolCallId, 'call_chunked');
    assert.equal(call.name, 'query_database');
    assert.deepEqual(JSON.parse(call.arguments), { query });
    worker.send({ type: 'tool_result', runId: 'run-1', toolCallId: 'call_chunked', isError: false, result: '{"rows":[]}' });
    assert.equal((await worker.waitFor((m) => m.type === 'finished')).status, 'SUCCEEDED');
    assert.equal(worker.messages.filter((m) => m.type === 'tool_call').length, 1, 'chunked deltas produce exactly one tool_call');
  } finally {
    await server.close();
  }
});

test('malformed streamed arguments become a model-visible error, not a host tool_call', async () => {
  const server = await startFixtureServer((body, count) => {
    if (count === 1) {
      return async (write) => {
        await write(chunk({ role: 'assistant', tool_calls: [{ index: 0, id: 'call_broken', type: 'function',
          function: { name: 'query_database', arguments: 'this is not json' } }] }));
        await write(chunk({}, 'tool_calls'));
        await write(usageChunk());
      };
    }
    const toolMessage = body.messages.find((m) => m.role === 'tool');
    assert.ok(toolMessage);
    assert.match(toolMessage.content, /Invalid arguments for tool "query_database"/);
    assert.match(toolMessage.content, /Received arguments/);
    return textResponse('无法理解参数');
  });
  try {
    const worker = startWorker();
    await waitHello(worker);
    worker.send(startMessage({ baseUrl: server.baseUrl }));
    const finished = await worker.waitFor((m) => m.type === 'finished');
    assert.equal(finished.status, 'SUCCEEDED');
    assert.equal(worker.messages.filter((m) => m.type === 'tool_call').length, 0);
    assert.equal(server.calls.length, 2);
  } finally {
    await server.close();
  }
});
