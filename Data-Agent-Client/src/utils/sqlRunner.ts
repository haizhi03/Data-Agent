import type { SqlScriptAnalysis } from '../types/sql';

/**
 * SQL script runner helpers.
 *
 * The SQL console executes a whole editor script through the backend's batch
 * API. This module holds the pure client-side logic for preparing a script for
 * execution — splitting it into statements and deciding which batch mode the
 * server will accept.
 *
 * Note: this is deliberately kept OUT of `utils/sql.ts` (SQL formatting) and
 * `utils/sqlGenerator/` (SQL generation). Formatting transforms text; the
 * generator turns data into SQL text; the runner takes SQL text and executes it.
 */

/** Statements the server accepts in ATOMIC batch mode (transactional DML / queries). */
const ATOMIC_FIRST_KEYWORDS = new Set(['SELECT', 'WITH', 'INSERT', 'UPDATE', 'DELETE', 'MERGE']);

/** One top-level statement plus its offsets in the original script. */
export interface SqlStatementSpan {
  /** Executable statement text, trimmed of surrounding whitespace. */
  sql: string;
  /** Offset of the first non-whitespace character (inclusive). */
  start: number;
  /** Offset just past the last non-whitespace character (exclusive). */
  end: number;
  /** Offset of the raw segment start (inclusive; may include leading whitespace/comments). */
  segmentStart: number;
  /** Offset of the raw segment end (the terminating `;`, or `sql.length` for the last). */
  segmentEnd: number;
}

/**
 * Lexically split SQL for execution-mode hints only (not cursor execution) on `;`, respecting string literals,
 * quoted identifiers, and `--` / `/* *​/` comments. Returns trimmed statement text
 * together with its offsets in the original string.
 */
export function splitSqlSpans(sql: string, mysql = false): SqlStatementSpan[] {
  const segments: Array<{ start: number; end: number }> = [];
  let segmentStart = 0;
  let singleQuote = false;
  let doubleQuote = false;
  let backtick = false;
  let lineComment = false;
  let blockComment = false;

  for (let i = 0; i < sql.length; i++) {
    const c = sql[i];
    const next = sql[i + 1];

    if (lineComment) {
      if (c === '\n' || c === '\r') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (c === '*' && next === '/') {
        i++;
        blockComment = false;
      }
      continue;
    }
    if (backtick) {
      if (c === '`' && next === '`') i++;
      else if (c === '`') backtick = false;
      continue;
    }
    if (mysql && (singleQuote || doubleQuote) && c === '\\') {
      i++;
      continue;
    }
    if (singleQuote) {
      if (c === "'" && next === "'") i++;
      else if (c === "'") singleQuote = false;
      continue;
    }
    if (doubleQuote) {
      if (c === '"' && next === '"') i++;
      else if (c === '"') doubleQuote = false;
      continue;
    }
    if ((c === '-' && next === '-' && (!mysql || !sql[i + 2] || /\s/.test(sql[i + 2]))) || (mysql && c === '#')) {
      if (c === '-') i++;
      lineComment = true;
      continue;
    }
    if (c === '/' && next === '*') {
      i++;
      blockComment = true;
      continue;
    }
    if (c === "'") {
      singleQuote = true;
      continue;
    }
    if (c === '"') {
      doubleQuote = true;
      continue;
    }
    if (c === '`') {
      backtick = true;
      continue;
    }
    if (c === ';') {
      segments.push({ start: segmentStart, end: i });
      segmentStart = i + 1;
    }
  }
  segments.push({ start: segmentStart, end: sql.length });

  const spans: SqlStatementSpan[] = [];
  for (const { start: segmentStart, end: segmentEnd } of segments) {
    const raw = sql.slice(segmentStart, segmentEnd);
    const trimmed = raw.trim();
    if (!trimmed) continue;
    spans.push({
      sql: trimmed,
      start: segmentStart + (raw.length - raw.trimStart().length),
      end: segmentStart + raw.trimEnd().length,
      segmentStart,
      segmentEnd,
    });
  }
  return spans;
}

/** Split a SQL script into top-level statements, returning trimmed statement text only. */
export function splitSqlStatements(sql: string, mysql = false): string[] {
  return splitSqlSpans(sql, mysql).map((span) => span.sql);
}

/** Use dialect parser spans to select a complete statement, including routine bodies.
 * A parse failure never falls back to executing the entire script or a fragment.
 */
export function statementSqlAtOffset(sql: string, offset: number, analysis: SqlScriptAnalysis): string {
  if (analysis.errors.length) throw new Error(analysis.errors[0].message);
  let previousEnd = 0;
  for (const statement of analysis.statements) {
    if (offset >= previousEnd && offset <= statement.endOffset) {
      return sql.slice(statement.startOffset, statement.endOffset);
    }
    previousEnd = statement.endOffset + (sql[statement.endOffset] === ';' ? 1 : 0);
  }
  return '';
}

/** Return the leading keyword of a statement, or '' when it starts with a comment. */
function firstKeyword(statement: string): string {
  let s = statement.trimStart();
  while (s.startsWith('--') || s.startsWith('#') || s.startsWith('/*')) {
    if (s.startsWith('--') || s.startsWith('#')) {
      const nl = s.indexOf('\n');
      s = nl === -1 ? '' : s.slice(nl + 1).trimStart();
    } else {
      const end = s.indexOf('*/');
      s = end === -1 ? '' : s.slice(end + 2).trimStart();
    }
  }
  const match = s.match(/^[\p{L}][\p{L}\p{N}_$#]*/u);
  return match ? match[0].toUpperCase() : '';
}

/**
 * True when the script contains a statement the server's ATOMIC batch mode rejects
 * (anything that is not a query or transactional DML — e.g. DROP/CREATE/ALTER/TRUNCATE).
 * Such scripts must run in STEPWISE mode.
 */
export function containsNonAtomicStatement(sql: string, mysql = false): boolean {
  return splitSqlStatements(sql, mysql).some((statement) => {
    const keyword = firstKeyword(statement);
    return keyword !== '' && !ATOMIC_FIRST_KEYWORDS.has(keyword);
  });
}
