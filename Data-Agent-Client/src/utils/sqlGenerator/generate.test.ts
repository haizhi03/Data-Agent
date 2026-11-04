import { describe, expect, it } from 'vitest';
import { resolveProfile } from './dialect';
import { toSqlLiteral } from './literals';
import { DEFAULT_SQL_GENERATE_OPTIONS, generateSql, type SqlGenerateRequest } from './generate';

const mysql = resolveProfile('mysql');
const dm = resolveProfile('dm');

function request(overrides: Partial<SqlGenerateRequest>): SqlGenerateRequest {
  return {
    statementType: 'INSERT',
    table: { schema: 'app', name: 'users' },
    columns: ['id', 'name', 'age', 'bio'],
    rows: [[1, 'Alice', 30, "she's great"]],
    pkColumns: ['id'],
    options: { ...DEFAULT_SQL_GENERATE_OPTIONS },
    ...overrides,
  };
}

describe('toSqlLiteral', () => {
  it('emits NULL, numbers, booleans and strings', () => {
    expect(toSqlLiteral(null, mysql)).toBe('NULL');
    expect(toSqlLiteral(undefined, mysql)).toBe('NULL');
    expect(toSqlLiteral(42, mysql)).toBe('42');
    expect(toSqlLiteral(3.14, mysql)).toBe('3.14');
    expect(toSqlLiteral(true, mysql)).toBe('1');
    expect(toSqlLiteral(false, dm)).toBe('0');
    expect(toSqlLiteral('hi', mysql)).toBe("'hi'");
  });

  it('escapes single quotes and (on MySQL) backslashes', () => {
    expect(toSqlLiteral("a'b", mysql)).toBe("'a''b'");
    expect(toSqlLiteral('a\\b', mysql)).toBe("'a\\\\b'");
    expect(toSqlLiteral('a\\b', dm)).toBe("'a\\b'");
  });

  it('renders binary as hex literal', () => {
    expect(toSqlLiteral(new Uint8Array([0x0a, 0xff]), mysql)).toBe("X'0AFF'");
    expect(toSqlLiteral([0x00, 0x01], mysql)).toBe("X'0001'");
  });
});

describe('generateSql INSERT', () => {
  it('qualifies the table and quotes identifiers', () => {
    const result = generateSql(request({}), mysql);
    expect(result.sql).toBe('INSERT INTO `app`.`users` (`id`, `name`, `age`, `bio`) VALUES (1, \'Alice\', 30, \'she\'\'s great\');');
  });

  it('omits the column list when requested', () => {
    const result = generateSql(
      request({ options: { ...DEFAULT_SQL_GENERATE_OPTIONS, omitColumnList: true } }),
      mysql,
    );
    expect(result.sql).toContain('INSERT INTO `app`.`users` VALUES');
    expect(result.sql).not.toContain('(`id`');
  });

  it('emits one statement per row when not batching', () => {
    const req = request({
      rows: [
        [1, 'Alice', 30, null],
        [2, 'Bob', 25, null],
      ],
      options: { ...DEFAULT_SQL_GENERATE_OPTIONS, batchMultiValues: false },
    });
    const result = generateSql(req, mysql);
    expect(result.statementCount).toBe(2);
    expect(result.sql.split(';').filter(Boolean).length).toBe(2);
  });

  it('wraps in a transaction for MySQL', () => {
    const result = generateSql(
      request({ options: { ...DEFAULT_SQL_GENERATE_OPTIONS, wrapInTransaction: true } }),
      mysql,
    );
    expect(result.sql).toContain('START TRANSACTION;');
    expect(result.sql).toContain('COMMIT;');
  });
});

describe('generateSql UPDATE/DELETE', () => {
  it('uses primary key columns in WHERE and emits IS NULL for null keys', () => {
    const req = request({
      statementType: 'UPDATE',
      rows: [[null, 'Alice', 30, null]],
      pkColumns: ['id'],
    });
    const result = generateSql(req, mysql);
    expect(result.whereColumns).toEqual(['id']);
    expect(result.sql).toContain('WHERE\n  `id` IS NULL;');
    expect(result.sql).not.toContain('`id` =');
  });

  it('falls back to all columns when no primary key matches', () => {
    const req = request({
      statementType: 'DELETE',
      pkColumns: ['nonexistent'],
    });
    const result = generateSql(req, mysql);
    expect(result.whereColumns).toEqual(['id', 'name', 'age', 'bio']);
  });

  it('excludes WHERE columns from the SET clause', () => {
    const req = request({
      statementType: 'UPDATE',
      rows: [[1, 'Alice', 31, 'x']],
      pkColumns: ['id'],
    });
    const result = generateSql(req, mysql);
    expect(result.sql).toContain('`name` = \'Alice\'');
    expect(result.sql).not.toContain('`id` = 1,\n');
  });
});

describe('generateSql SELECT', () => {
  it('generates OR-combined row filters', () => {
    const req = request({
      statementType: 'SELECT',
      rows: [
        [1, 'Alice', 30, null],
        [2, 'Bob', 25, null],
      ],
    });
    const result = generateSql(req, mysql);
    expect(result.sql).toContain('SELECT');
    expect(result.sql).toContain('OR');
    expect(result.sql).toContain('`id` = 1');
  });
});

describe('dialect quoting', () => {
  it('uses double quotes for DM and backticks for MySQL', () => {
    expect(generateSql(request({}), dm).sql).toContain('"app"."users"');
    expect(generateSql(request({}), mysql).sql).toContain('`app`.`users`');
  });
});

describe('UPDATE fallback and console transactions', () => {
  it('generates valid SET assignments without a primary key', () => {
    const result = generateSql(request({ statementType: 'UPDATE', pkColumns: [] }), mysql);
    expect(result.sql).toContain("SET\n  `id` = 1,");
    expect(result.sql).toContain("WHERE\n  `id` = 1 AND");
    expect(result.sql).not.toContain('SET\n\nWHERE');
  });

  it('handles a table containing only key columns', () => {
    const result = generateSql(request({ statementType: 'UPDATE', columns: ['id'], rows: [[1]] }), mysql);
    expect(result.sql).toBe('UPDATE `app`.`users`\nSET\n  `id` = 1\nWHERE\n  `id` = 1;');
  });

  it.each([mysql, dm])('keeps console SQL compatible with server-managed transactions', (profile) => {
    const result = generateSql(request({ options: { ...DEFAULT_SQL_GENERATE_OPTIONS, wrapInTransaction: true } }), profile);
    expect(result.sql).toContain('COMMIT;');
    expect(result.consoleSql).not.toMatch(/\b(COMMIT|BEGIN|START TRANSACTION)\b/);
    expect(result.consoleSql).toMatch(/^INSERT INTO/);
    expect(result.statementCount).toBe(1);
  });
});
