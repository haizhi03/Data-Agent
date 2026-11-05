/**
 * stdio JSON-lines protocol for the Data-Agent Pi worker.
 *
 * Inbound (Java host -> worker):
 *   start       { type, runId, sessionId, systemPrompt, message, model, apiKey, role?, limits?, context? }
 *   tool_result { type, runId, toolCallId, isError, result }
 *   cancel      { type, runId }
 *
 * Outbound (worker -> Java host), always on stdout, one JSON object per line:
 *   hello       { type, protocol, piAgentCore, piAi }
 *   text_delta  { type, runId, delta }
 *   tool_call   { type, runId, toolCallId, name, arguments }   // arguments is a JSON string
 *   finished    { type, runId, status, errorCode?, usage? }    // exactly once per run
 *   error       { type, errorCode, detail? }                    // protocol-level, no run
 *
 * Diagnostics go to stderr only. Credentials never appear on stdout/stderr.
 */

export const PROTOCOL_VERSION = 1;
export const MAX_INBOUND_LINE_BYTES = 256 * 1024;
export const MAX_TOOL_RESULT_CHARS = 64 * 1024;
export const MAX_RUN_TEXT_CHARS = 256 * 1024;

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
  process.stderr.write(`[pi-worker] ${line.replace(/sk-[A-Za-z0-9_-]+/g, 'sk-***').slice(0, 2000)}\n`);
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
