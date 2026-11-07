/**
 * Scripted OpenAI-compatible streaming chat-completions fixture.
 * No real model is involved; each test installs a handler that decides the
 * response from the request body and the per-test call count.
 */
import http from 'node:http';

export function chunk(delta, finishReason = null) {
  return { id: 'chatcmpl-fixture', object: 'chat.completion.chunk', created: 0, model: 'fixture-model',
    choices: [{ index: 0, delta, finish_reason: finishReason }] };
}

export function usageChunk(input = 11, output = 7) {
  return { id: 'chatcmpl-fixture', object: 'chat.completion.chunk', created: 0, model: 'fixture-model',
    choices: [], usage: { prompt_tokens: input, completion_tokens: output, total_tokens: input + output } };
}

export function textResponse(text, { chunkSize = 4, delayMs = 0 } = {}) {
  const parts = [];
  for (let i = 0; i < text.length; i += chunkSize) parts.push(text.slice(i, i + chunkSize));
  return async (write) => {
    for (const [index, part] of parts.entries()) {
      await write(chunk(index === 0 ? { role: 'assistant', content: part } : { content: part }), delayMs);
    }
    await write(chunk({}, 'stop'), delayMs);
    await write(usageChunk(), delayMs);
  };
}

export function toolCallResponse(toolCalls, { delayMs = 0 } = {}) {
  return async (write) => {
    let first = true;
    for (const call of toolCalls) {
      const delta = { tool_calls: [{ index: 0, id: call.id ?? 'call_fixture', type: 'function',
        function: { name: call.name, arguments: JSON.stringify(call.args ?? {}) } }] };
      if (first) delta.role = 'assistant';
      await write(chunk(delta), delayMs);
      first = false;
    }
    await write(chunk({}, 'tool_calls'), delayMs);
    await write(usageChunk(), delayMs);
  };
}

/**
 * @param {(body: object, callCount: number) => Promise<((write: (c: object, d?: number) => Promise<void>) => Promise<void>) | null> | object} handler
 *   Return a stream plan (see textResponse/toolCallResponse), or { status, json } to answer a plain error.
 */
export async function startFixtureServer(handler) {
  const calls = [];
  const server = http.createServer((req, res) => {
    if (req.method !== 'POST' || !req.url?.endsWith('/chat/completions')) {
      res.writeHead(404).end('not found');
      return;
    }
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', async () => {
      const body = JSON.parse(raw);
      calls.push(body);
      const plan = await handler(body, calls.length);
      if (plan && typeof plan === 'object' && 'status' in plan) {
        res.writeHead(plan.status, { 'content-type': 'application/json' }).end(JSON.stringify(plan.json ?? {}));
        return;
      }
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      });
      const open = { aborted: false };
      req.on('close', () => (open.aborted = true));
      const write = async (payload, delayMs = 0) => {
        if (delayMs) await new Promise((r) => setTimeout(r, delayMs));
        if (open.aborted) throw new Error('client aborted');
        res.write(`data: ${JSON.stringify(payload)}\n\n`);
      };
      try {
        if (plan) await plan(write);
        if (!open.aborted) res.write('data: [DONE]\n\n');
      } catch {
        /* client went away mid-stream */
      }
      if (!res.writableEnded) res.end();
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}/v1`,
    calls,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}
