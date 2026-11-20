import { describe, expect, it } from 'vitest';
import type { TableStructure } from '../types/tableStructure';
import {
  acceptPreview,
  adoptStructure,
  applyColumnLength,
  canExecute,
  renameColumnReferences,
  structuresEqual,
  togglePrimaryKey,
  toStructurePayload,
} from './tableStructureDraft';

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
      generationExpression: null,
      generationStored: false,
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
      generationExpression: null,
      generationStored: false,
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

  it('executes only the preview for the current draft revision', () => {
    expect(canExecute(1, { revision: 1 }, 2)).toBe(true);
    expect(canExecute(2, { revision: 1 }, 2)).toBe(false);
    expect(canExecute(2, { revision: 2 }, 0)).toBe(false);
    expect(acceptPreview(1, 2, { statements: ['drop column'] })).toBeNull();
    expect(acceptPreview(2, 2, { statements: ['drop column'] })).toEqual({ statements: ['drop column'] });
  });

  it('renames index and foreign key columns with the column', () => {
    const draft = adoptStructure({
      ...loaded,
      indexes: [{
        name: 'idx_name',
        originalName: 'idx_name',
        unique: false,
        type: 'BTREE',
        columns: ['name'],
        prefixLengths: [10],
      }],
      foreignKeys: [{
        name: 'fk_name',
        originalName: 'fk_name',
        columns: ['name'],
        referencedSchema: 'crm',
        referencedTable: 'customer',
        referencedColumns: ['id'],
        onUpdate: 'RESTRICT',
        onDelete: 'CASCADE',
      }],
    });
    const next = renameColumnReferences({
      ...draft,
      columns: draft.columns.map((column) => (column.name === 'name' ? { ...column, name: 'title' } : column)),
    }, 'name', 'title');
    expect(next.indexes[0].columnText).toBe('title(10)');
    expect(next.foreignKeys[0].columnsText).toBe('title');
    expect(next.primaryKey?.columns).toEqual(['id']);
  });
});
