import { describe, expect, it } from 'vitest';
import { extractSqlVariables, substituteSqlVariables, substituteSqlVariablesAtOffset } from './sqlVariables';

describe('SQL parameter quoting', () => {
  it('escapes the actual string or identifier delimiter', () => {
    expect(substituteSqlVariables('SELECT "${name}";', { name: 'a"b' }, true)).toBe('SELECT "a""b";');
    expect(substituteSqlVariables("SELECT '${name}';", { name: "a'b\\c" }, true)).toBe("SELECT 'a''b\\\\c';");
    expect(substituteSqlVariables('SELECT "${column}" FROM "${table}";', { column: 'a"b', table: 't' }, false))
      .toBe('SELECT "a""b" FROM "t";');
    expect(substituteSqlVariables('SELECT `${column}`;', { column: 'a`b' }, true)).toBe('SELECT `a``b`;');
  });

  it('ignores dialect comments and leaves unknown variables alone', () => {
    const sql = '--${hidden}\nSELECT ${id}; /* ${block} */';
    expect(extractSqlVariables(sql, false)).toEqual([{ name: 'id', occurrences: 1 }]);
    expect(extractSqlVariables('# ${hidden}\nSELECT ${id}', true)).toEqual([{ name: 'id', occurrences: 1 }]);
    expect(substituteSqlVariables('SELECT ${known}, ${unknown}', { known: '1' }, true)).toBe('SELECT 1, ${unknown}');
  });

  it('keeps the cursor on the same SQL statement after substitutions', () => {
    const sql = "SELECT '${name}'; DELETE FROM t WHERE id=${id};";
    const input = substituteSqlVariablesAtOffset(sql, { name: 'a much longer name', id: '12' }, true, sql.indexOf('DELETE'));
    expect(input.cursorOffset).toBe(input.sql.indexOf('DELETE'));
    const inside = substituteSqlVariablesAtOffset('SELECT ${name};', { name: '123456789' }, true, 10);
    expect(inside.cursorOffset).toBe(7);
  });
});
