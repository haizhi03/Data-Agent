import { describe, expect, it } from 'vitest';
import type { TableStructure } from '../types/tableStructure';
import { adoptStructure, applyColumnLength, structuresEqual, togglePrimaryKey, toStructurePayload } from './tableStructureDraft';

const loaded: TableStructure = {
  name: 'crm_activity',
  comment: '活动',
  engine: 'InnoDB',
  charset: 'utf8mb4',
  collation: 'utf8mb4_unicode_ci',
  columns: [
    {
      name: 'id',
      originalName: 'id',
      typeName: 'bigint',
      columnType: 'bigint',
      length: null,
      scale: null,
      nullable: false,
      autoIncrement: true,
      unsigned: false,
      defaultKind: 'NONE',
      defaultValue: null,
      onUpdate: null,
      comment: '',
      charset: null,
      collation: null,
    },
    {
      name: 'name',
      originalName: 'name',
      typeName: 'varchar',
      columnType: 'varchar(100)',
      length: 100,
      scale: null,
      nullable: true,
      autoIncrement: false,
      unsigned: false,
      defaultKind: 'NONE',
      defaultValue: null,
      onUpdate: null,
      comment: '',
      charset: null,
      collation: null,
    },
  ],
  primaryKey: { name: 'PRIMARY', columns: ['id'] },
  indexes: [{
    name: 'idx_name',
    originalName: 'idx_name',
    unique: false,
    type: 'BTREE',
    columns: ['name'],
    prefixLengths: [null],
  }],
  foreignKeys: [],
  checks: [],
};

describe('tableStructureDraft', () => {
  it('round-trips a loaded structure without marking it dirty', () => {
    const payload = toStructurePayload(adoptStructure(loaded));
    expect(structuresEqual(payload, loaded)).toBe(true);
  });

  it('keeps the original column name when the draft renames it', () => {
    const draft = adoptStructure(loaded);
    draft.columns[1] = { ...draft.columns[1], name: 'title' };
    const payload = toStructurePayload(draft);
    expect(payload.columns[1].originalName).toBe('name');
    expect(payload.columns[1].name).toBe('title');
    expect(structuresEqual(payload, loaded)).toBe(false);
  });

  it('rewrites a sized column type when the length changes', () => {
    const draft = adoptStructure(loaded);
    const next = applyColumnLength(draft.columns[1], 200);
    expect(next.columnType).toBe('varchar(200)');
    expect(next.length).toBe(200);
  });

  it('drops the primary key when its last column is unchecked', () => {
    expect(togglePrimaryKey(loaded.primaryKey, 'id', false)).toBeNull();
  });
});
