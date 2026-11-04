import { describe, expect, it } from 'vitest';
import {
  containsNonAtomicStatement,
  splitSqlSpans,
  splitSqlStatements,
  statementSqlAtOffset,
} from './sqlRunner';

describe('splitSqlStatements', () => {
  it('splits on top-level semicolons only', () => {
    expect(splitSqlStatements('SELECT 1; SELECT 2;')).toEqual(['SELECT 1', 'SELECT 2']);
    expect(splitSqlStatements("SELECT 'a;b'; SELECT 2;")).toEqual(["SELECT 'a;b'", 'SELECT 2']);
    expect(splitSqlStatements('SELECT 1 /* ; */; SELECT 2;')).toEqual(['SELECT 1 /* ; */', 'SELECT 2']);
    expect(splitSqlStatements('-- c;\nSELECT 1;')).toEqual(['-- c;\nSELECT 1']);
    expect(splitSqlStatements('')).toEqual([]);
  });
});

describe('splitSqlSpans', () => {
  it('tracks trimmed offsets and raw segment ranges', () => {
    const [a, b] = splitSqlSpans('SELECT 1; SELECT 2;');
    expect(a).toEqual({ sql: 'SELECT 1', start: 0, end: 8, segmentStart: 0, segmentEnd: 8 });
    expect(b).toEqual({ sql: 'SELECT 2', start: 10, end: 18, segmentStart: 9, segmentEnd: 18 });
  });

  it('skips empty segments', () => {
    expect(splitSqlSpans('SELECT 1;;')).toHaveLength(1);
    expect(splitSqlSpans('   ')).toHaveLength(0);
  });
});

describe('statementSqlAtOffset', () => {
  const analysis = (spans: Array<[number, number]>) => ({
    statements: spans.map(([startOffset, endOffset]) => ({ startOffset, endOffset })) as import('../types/sql').SqlStatementAnalysis[],
    errors: [],
  });

  it('uses parser ranges including semicolon boundaries and leading whitespace', () => {
    const sql = 'SELECT 1; SELECT 2;';
    const parsed = analysis([[0, 8], [10, 18]]);
    expect(statementSqlAtOffset(sql, 0, parsed)).toBe('SELECT 1');
    expect(statementSqlAtOffset(sql, 8, parsed)).toBe('SELECT 1');
    expect(statementSqlAtOffset(sql, 9, parsed)).toBe('SELECT 2');
    expect(statementSqlAtOffset(sql, 12, parsed)).toBe('SELECT 2');
    expect(statementSqlAtOffset(sql, sql.length, parsed)).toBe('');
  });

  it('keeps the complete procedure when the cursor is on an internal DELETE', () => {
    const sql = 'CREATE OR REPLACE PROCEDURE p AS BEGIN INSERT INTO t VALUES(1); DELETE FROM t; END;';
    expect(statementSqlAtOffset(sql, sql.indexOf('DELETE'), analysis([[0, sql.length]]))).toBe(sql);
  });

  it('does not execute a fallback script if parsing fails or cursor is outside statements', () => {
    expect(() => statementSqlAtOffset('bad SQL', 2, { statements: [], errors: [{ line: 1, column: 0, message: 'Invalid SQL' }] })).toThrow('Invalid SQL');
    expect(statementSqlAtOffset('SELECT 1;  ', 11, analysis([[0, 8]]))).toBe('');
  });
});

describe('containsNonAtomicStatement', () => {
  it('returns false for queries and transactional DML', () => {
    expect(containsNonAtomicStatement('SELECT * FROM t;')).toBe(false);
    expect(containsNonAtomicStatement('WITH cte AS (SELECT 1) SELECT * FROM cte;')).toBe(false);
    expect(containsNonAtomicStatement('INSERT INTO t VALUES (1); DELETE FROM t WHERE id=1;')).toBe(false);
    expect(containsNonAtomicStatement('UPDATE t SET x=1 WHERE id=1;')).toBe(false);
    expect(containsNonAtomicStatement('')).toBe(false);
  });

  it('returns true for DDL and other non-atomic statements', () => {
    expect(containsNonAtomicStatement('DROP TABLE demo;')).toBe(true);
    expect(containsNonAtomicStatement('CREATE TABLE t (id INT);')).toBe(true);
    expect(containsNonAtomicStatement('ALTER TABLE t ADD c INT;')).toBe(true);
    expect(containsNonAtomicStatement('TRUNCATE TABLE t;')).toBe(true);
    expect(containsNonAtomicStatement('SHOW TABLES;')).toBe(true);
  });

  it('returns true when any statement in a mixed script is non-atomic', () => {
    expect(containsNonAtomicStatement('SELECT 1; DROP TABLE demo;')).toBe(true);
  });
});

it('honors MySQL escapes, backticks and hash comments for mode hints', () => {
  expect(splitSqlStatements(String.raw`SELECT 'it\'s;a'; SELECT 2;`, true))
    .toEqual([String.raw`SELECT 'it\'s;a'`, 'SELECT 2']);
  expect(splitSqlStatements('SELECT `a;b`; # ignored;\nSELECT 2;', true))
    .toEqual(['SELECT `a;b`', '# ignored;\nSELECT 2']);
  expect(containsNonAtomicStatement('# ignored;\nSELECT 1;', true)).toBe(false);
});
