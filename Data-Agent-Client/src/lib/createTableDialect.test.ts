import { describe, expect, it } from 'vitest';
import {
  buildDmCreateIndexStatement,
  buildInlineIndexDefinition,
  getCreateTableColumnTypes,
} from './createTableDialect';

describe('create-table dialect types', () => {
  it('offers DM-specific types only for DM', () => {
    expect(getCreateTableColumnTypes('dm')).toEqual(expect.arrayContaining([
      'VARCHAR2(255)',
      'NUMBER(10,2)',
      'CLOB',
      'BLOB',
      'TIMESTAMP(6) WITH TIME ZONE',
    ]));
    expect(getCreateTableColumnTypes('mysql')).not.toContain('VARCHAR2(255)');
    expect(getCreateTableColumnTypes('mysql')).not.toContain('NUMBER(10,2)');
  });
});

describe('create-table indexes', () => {
  const index = { name: 'idx_user_name', columns: 'name, department_id' };

  it('keeps the MySQL inline KEY definition', () => {
    expect(buildInlineIndexDefinition(index, 'mysql'))
      .toBe('  KEY `idx_user_name` (`name`, `department_id`)');
  });

  it('builds a standalone schema-qualified DM CREATE INDEX statement', () => {
    expect(buildDmCreateIndexStatement('HR', 'users', index, 'dm'))
      .toBe('CREATE INDEX "HR"."idx_user_name" ON "HR"."users" ("name", "department_id")');
  });
});
