/**
 * Sidecar journal: the worker's own durable record, next to (not inside) the
 * pi-durable storage. Three record kinds, one JSON object per line:
 *
 *   { kind: 'session', sessionId, conversationId }
 *   { kind: 'run',     sessionId, runId, submissionId, requestId, model }   // model: non-secret config, NO apiKey
 *   { kind: 'event',   sessionId, seq, event }   // every run-scoped outbound event
 *
 * The journal answers two questions after a restart:
 *   - which sessions/runs existed and which durable objects back them;
 *   - which outbound events were already emitted (per-session seq), so an
 *     attaching host can replay from afterSeq and a finished event is never
 *     emitted twice.
 *
 * Memory mode keeps the same structure in process memory: nothing survives a
 * restart there, which mirrors MemoryStorage semantics exactly.
 */
import fs from 'node:fs';
import path from 'node:path';

export class Journal {
  /**
   * @param {string|null} filePath null for memory mode
   */
  constructor(filePath) {
    this.filePath = filePath ?? null;
    /** @type {Map<string, {conversationId: string}>} */
    this.sessions = new Map();
    /** @type {Map<string, {sessionId: string, submissionId: string|number, requestId: string, finished: boolean}>} */
    this.runs = new Map(); // keyed by runId
    /** @type {Map<string, Array<{seq: number, event: object}>>} */
    this.events = new Map(); // keyed by sessionId
    /** @type {Map<string, number>} */
    this.lastSeq = new Map();
    if (this.filePath) this.#load();
  }

  #load() {
    if (!fs.existsSync(this.filePath)) return;
    const lines = fs.readFileSync(this.filePath, 'utf8').split('\n');
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      let record;
      try {
        record = JSON.parse(trimmed);
      } catch {
        continue; // a torn last line from a crash is dropped
      }
      this.#apply(record);
    }
  }

  #apply(record) {
    if (record.kind === 'session') {
      this.sessions.set(record.sessionId, { conversationId: record.conversationId });
    } else if (record.kind === 'run') {
      this.runs.set(record.runId, {
        sessionId: record.sessionId,
        submissionId: record.submissionId,
        requestId: record.requestId,
        model: record.model, // non-secret model config for credential-less re-registration
        finished: false,
      });
    } else if (record.kind === 'event') {
      let list = this.events.get(record.sessionId);
      if (!list) this.events.set(record.sessionId, (list = []));
      list.push({ seq: record.seq, event: record.event });
      this.lastSeq.set(record.sessionId, Math.max(this.lastSeq.get(record.sessionId) ?? 0, record.seq));
      if (record.event.type === 'finished') {
        const run = this.runs.get(record.event.runId);
        if (run) run.finished = true;
      }
    }
  }

  #persist(record) {
    if (!this.filePath) return;
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    fs.appendFileSync(this.filePath, JSON.stringify(record) + '\n');
  }

  registerSession(sessionId, conversationId) {
    if (this.sessions.has(sessionId)) return;
    const record = { kind: 'session', sessionId, conversationId };
    this.#persist(record);
    this.#apply(record);
  }

  registerRun(sessionId, runId, submissionId, requestId, model) {
    if (this.runs.has(runId)) return;
    const record = { kind: 'run', sessionId, runId, submissionId, requestId, model };
    this.#persist(record);
    this.#apply(record);
  }

  /**
   * Journal and return the next per-session event. The record is persisted
   * BEFORE the caller sends it, so a crash leaves at worst an unsent but
   * replayable event.
   */
  appendEvent(sessionId, event) {
    const seq = (this.lastSeq.get(sessionId) ?? 0) + 1;
    const stamped = { ...event, sessionId, seq };
    const record = { kind: 'event', sessionId, seq, event: stamped };
    this.#persist(record);
    this.#apply(record);
    return stamped;
  }

  /** Events of one session with seq > afterSeq, in order. */
  eventsAfter(sessionId, afterSeq) {
    return (this.events.get(sessionId) ?? []).filter((entry) => entry.seq > afterSeq);
  }

  hasFinished(runId) {
    return this.runs.get(runId)?.finished === true;
  }

  /** Recovery view: every run and whether its finished event was journaled. */
  recoveredRuns() {
    return [...this.runs.entries()].map(([runId, run]) => ({
      sessionId: run.sessionId,
      runId,
      status: run.finished ? 'FINISHED' : 'RUNNING',
    }));
  }
}
