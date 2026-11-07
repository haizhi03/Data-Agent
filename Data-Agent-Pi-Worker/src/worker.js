#!/usr/bin/env node
/**
 * Data-Agent Pi worker entry point.
 *
 * Java starts one worker process per temporary conversation and talks to it
 * over stdio with the JSON-lines protocol in protocol.js. stdout carries only
 * protocol messages; diagnostics go to stderr. The worker exits when stdin
 * closes or after a period of inactivity.
 */
import { PiSession } from './session.js';
import {
  PROTOCOL_VERSION,
  MAX_INBOUND_LINE_BYTES,
  createStdoutSender,
  logDiagnostic,
  readJsonLines,
} from './protocol.js';
import { PI_AI_VERSION } from '@earendil-works/pi-ai';

const IDLE_EXIT_MS = 30 * 60 * 1000;

const send = createStdoutSender();
const sessions = new Map();

let idleTimer = null;
function touch() {
  if (idleTimer) clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    logDiagnostic('idle timeout, exiting');
    shutdown(0);
  }, IDLE_EXIT_MS);
  idleTimer.unref?.();
}

function shutdown(code) {
  for (const session of sessions.values()) session.dispose();
  sessions.clear();
  process.exit(code);
}

function validateStart(msg) {
  if (typeof msg.runId !== 'string' || !msg.runId) return 'runId required';
  if (typeof msg.sessionId !== 'string' || !msg.sessionId) return 'sessionId required';
  if (typeof msg.systemPrompt !== 'string' || msg.systemPrompt.length > 64000) return 'systemPrompt invalid';
  if (typeof msg.message !== 'string' || !msg.message || msg.message.length > 128000) return 'message invalid';
  if (typeof msg.apiKey !== 'string' || !msg.apiKey) return 'apiKey required';
  if (typeof msg.model !== 'object' || msg.model === null) return 'model required';
  if (msg.role !== undefined && !['assistant', 'explorer'].includes(msg.role)) return 'role invalid';
  if (msg.toolProfile !== undefined && !['general', 'schema'].includes(msg.toolProfile)) return 'toolProfile invalid';
  for (const [key, maximum] of [['maxModelCalls', 16], ['maxToolCalls', 32]]) {
    const value = msg.limits?.[key];
    if (value !== undefined && (!Number.isInteger(value) || value < 1 || value > maximum)) return 'limits invalid';
  }
  return null;
}

function onMessage(msg) {
  touch();
  if (typeof msg !== 'object' || msg === null || typeof msg.type !== 'string') {
    send({ type: 'error', errorCode: 'PI_PROTOCOL_ERROR', detail: 'message must have a type' });
    return;
  }
  switch (msg.type) {
    case 'start': {
      const problem = validateStart(msg);
      if (problem) {
        send({
          type: 'finished',
          runId: typeof msg.runId === 'string' ? msg.runId : 'unknown',
          status: 'FAILED',
          errorCode: 'PI_PROTOCOL_ERROR',
        });
        logDiagnostic('rejected start:', problem);
        return;
      }
      let session = sessions.get(msg.sessionId);
      if (!session) {
        if (sessions.size >= 8) {
          send({ type: 'finished', runId: msg.runId, status: 'FAILED', errorCode: 'PI_SESSION_CAPACITY' });
          return;
        }
        session = new PiSession(msg.sessionId, send);
        sessions.set(msg.sessionId, session);
      }
      // start() emits the terminal finished message itself; nothing else awaits it.
      void session.start(msg).catch((error) => {
        logDiagnostic('unexpected start failure:', error);
        send({ type: 'finished', runId: msg.runId, status: 'FAILED', errorCode: 'PI_WORKER_ERROR' });
      });
      return;
    }
    case 'tool_result': {
      if (typeof msg.runId !== 'string' || typeof msg.toolCallId !== 'string') return;
      for (const session of sessions.values()) {
        if (session.resolveTool(msg.runId, msg.toolCallId, msg.isError === true, msg.result)) return;
      }
      logDiagnostic('late tool_result dropped:', msg.runId, msg.toolCallId);
      return;
    }
    case 'cancel': {
      if (typeof msg.runId !== 'string') return;
      for (const session of sessions.values()) session.cancel(msg.runId);
      return;
    }
    default:
      send({ type: 'error', errorCode: 'PI_PROTOCOL_ERROR', detail: `unknown type ${msg.type}`.slice(0, 200) });
  }
}

process.on('uncaughtException', (error) => {
  logDiagnostic('uncaughtException:', error);
  shutdown(1);
});
process.on('unhandledRejection', (error) => {
  logDiagnostic('unhandledRejection:', error);
  shutdown(1);
});

send({
  type: 'hello',
  protocol: PROTOCOL_VERSION,
  piAgentCore: '1.5.0',
  piAi: typeof PI_AI_VERSION === 'string' ? PI_AI_VERSION : '1.5.0',
});
touch();
readJsonLines(process.stdin, {
  maxBytes: MAX_INBOUND_LINE_BYTES,
  onMessage,
  onViolation: (error) => {
    logDiagnostic('protocol violation:', error);
    send({ type: 'error', errorCode: 'PI_PROTOCOL_ERROR', detail: error.message.slice(0, 200) });
  },
  onEnd: () => shutdown(0),
});
