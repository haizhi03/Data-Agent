/**
 * stdio JSON-lines protocol for the durable prototype worker (protocol v2).
 *
 * Inbound (Java host -> worker):
 *   start       { type, runId, sessionId, systemPrompt, message, model, apiKey,
 *                 idempotencyKey?, role?, toolProfile?, limits? }
 *   tool_result { type, runId, toolCallId, isError, result }
 *   cancel      { type, runId }
 *   attach      { type, sessionId, afterSeq }   // replay journaled events with seq > afterSeq
 *
 * Outbound (worker -> Java host), one JSON object per line:
 *   hello       { type, protocol: 2, piDurable, piAi, storage, recoveredRuns: [{sessionId, runId, status}] }
 *   text_delta  { type, sessionId, runId, seq, delta }
 *   text_reset  { type, sessionId, runId, seq }   // drop assembled text; reassemble from following deltas
 *   tool_call   { type, sessionId, runId, seq, toolCallId, name, arguments }  // arguments is a JSON string
 *   finished    { type, sessionId, runId, seq, status, errorCode?, reason?, usage? }  // exactly once per run (in the journal)
 *   attached    { type, sessionId, replayed }     // answer to attach: replayed event count
 *   error       { type, errorCode, detail? }      // protocol-level, no seq
 *
 * Every run-scoped outbound event carries a per-session increasing `seq` and is
 * journaled before it is sent, so a reattached host can replay what it missed.
 */

export const PROTOCOL_VERSION = 2;
export const MAX_INBOUND_LINE_BYTES = 256 * 1024;
export const MAX_TOOL_RESULT_CHARS = 64 * 1024;

export function createStdoutSender() {
  let chain = Promise.resolve();
  return function send(message) {
    const line = JSON.stringify(message);
    chain = chain.then(
      () =>
        new Promise((resolve) => {
          process.stdout.write(line + '\n', () => resolve());
        }),
    );
    return chain;
  };
}

export function logDiagnostic(...args) {
  const line = args
    .map((value) => (value instanceof Error ? `${value.name}: ${value.message}` : String(value)))
    .join(' ');
  // Defensive: never emit secrets even if a caller accidentally forwards one.
  process.stderr.write(`[pi-durable-worker] ${line.replace(/sk-[A-Za-z0-9_-]+/g, 'sk-***').slice(0, 2000)}\n`);
}

/** Read JSON lines from stdin with a hard per-line size cap. */
export function readJsonLines(input, { maxBytes, onMessage, onViolation, onEnd }) {
  let buffer = '';
  let overflow = false;
  input.setEncoding('utf8');
  input.on('data', (chunk) => {
    if (overflow) return;
    buffer += chunk;
    for (;;) {
      const newline = buffer.indexOf('\n');
      if (newline < 0) {
        if (Buffer.byteLength(buffer, 'utf8') > maxBytes) {
          overflow = true;
          onViolation(new Error(`inbound line exceeds ${maxBytes} bytes`));
        }
        return;
      }
      const line = buffer.slice(0, newline);
      buffer = buffer.slice(newline + 1);
      if (Buffer.byteLength(line, 'utf8') > maxBytes) {
        overflow = true;
        onViolation(new Error(`inbound line exceeds ${maxBytes} bytes`));
        return;
      }
      const trimmed = line.trim();
      if (!trimmed) continue;
      let parsed;
      try {
        parsed = JSON.parse(trimmed);
      } catch (error) {
        onViolation(new Error(`invalid JSON line: ${error.message}`));
        continue;
      }
      onMessage(parsed);
    }
  });
  input.on('end', () => onEnd?.());
}
