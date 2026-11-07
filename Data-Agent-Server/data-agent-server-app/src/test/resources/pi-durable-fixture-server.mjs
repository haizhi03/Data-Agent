/**
 * Standalone scripted OpenAI-compatible streaming chat-completions fixture for the
 * real durable-worker interop tests (LocalPiDurableInteropTest). No real model is
 * involved. Prints "READY <baseUrl>" on stdout once listening, "CALL <n>" per
 * request, and "STREAMED <n>" markers while streaming, so the Java test can
 * synchronize fault injection without touching the worker.
 *
 * Usage: node pi-durable-fixture-server.mjs <scenario>
 *   tool   - first request without a tool message -> one list_tables tool call;
 *            continuation requests -> a short text answer.
 *   stream - first request -> a long, slow text stream (killed mid-flight by the
 *            test); later requests -> a short final answer.
 */
import http from 'node:http';

const scenario = process.argv[2] ?? 'tool';
let calls = 0;

function chunk(delta, finishReason = null) {
  return { id: 'chatcmpl-fixture', object: 'chat.completion.chunk', created: 0, model: 'fixture-model',
    choices: [{ index: 0, delta, finish_reason: finishReason }] };
}
function usageChunk(input = 11, output = 7) {
  return { id: 'chatcmpl-fixture', object: 'chat.completion.chunk', created: 0, model: 'fixture-model',
    choices: [], usage: { prompt_tokens: input, completion_tokens: output, total_tokens: input + output } };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || !req.url?.endsWith('/chat/completions')) {
    res.writeHead(404).end('not found');
    return;
  }
  let raw = '';
  req.on('data', (c) => (raw += c));
  req.on('end', async () => {
    calls += 1;
    const seq = calls;
    process.stdout.write(`CALL ${seq}\n`);
    const body = JSON.parse(raw);
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    });
    const open = { aborted: false };
    // Note: req 'close' fires once the request body is fully read, NOT when the
    // client disconnects; use res 'close' to detect the client going away.
    res.on('close', () => (open.aborted = true));
    const write = async (payload, delayMs = 0) => {
      if (delayMs) await sleep(delayMs);
      if (open.aborted) throw new Error('client aborted');
      res.write(`data: ${JSON.stringify(payload)}\n\n`);
    };
    const writeText = async (text, chunkSize = 4, delayMs = 0, marker = 0) => {
      let n = 0;
      for (let i = 0; i < text.length; i += chunkSize, n++) {
        await write(chunk(n === 0 ? { role: 'assistant', content: text.slice(i, i + chunkSize) } : { content: text.slice(i, i + chunkSize) }), delayMs);
        if (marker > 0 && n === marker) process.stdout.write(`STREAMED ${n}\n`);
      }
      await write(chunk({}, 'stop'), delayMs);
    };
    try {
      if (scenario === 'tool') {
        if (!body.messages.some((m) => m.role === 'tool')) {
          await write(chunk({ role: 'assistant', tool_calls: [{ index: 0, id: 'call_durable_1', type: 'function',
            function: { name: 'list_tables', arguments: '{}' } }] }));
          await write(chunk({}, 'tool_calls'));
        } else {
          await writeText('durable tool loop completed');
        }
      } else if (calls === 1) {
        await writeText('x'.repeat(6000), 40, 5, 20); // slow: killed mid-stream by the test
      } else {
        await writeText('recovered final answer');
      }
      await write(usageChunk());
      if (!open.aborted) res.write('data: [DONE]\n\n');
    } catch {
      /* client went away mid-stream */
    }
    if (!res.writableEnded) res.end();
  });
});
server.listen(0, '127.0.0.1', () => {
  process.stdout.write(`READY http://127.0.0.1:${server.address().port}/v1\n`);
});
