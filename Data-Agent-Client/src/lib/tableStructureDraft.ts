import type {
  ColumnDraft,
  ForeignKeyDraft,
  IndexDraft,
  StructureCheck,
  StructureForeignKey,
  StructureIndex,
  StructurePrimaryKey,
  TableDraft,
  TableStructure,
} from '../types/tableStructure';

let sequence = 0;

export function newStructureClientId(): string {
  sequence += 1;
  return `structure-${sequence}-${Math.random().toString(36).slice(2, 8)}`;
}

export function formatIndexColumns(columns: string[], prefixLengths?: Array<number | null> | null): string {
  return columns.map((column, index) => {
    const prefix = prefixLengths?.[index];
    return prefix ? `${column}(${prefix})` : column;
  }).join(', ');
}

export function parseIndexColumns(text: string): { columns: string[]; prefixLengths: Array<number | null> } {
  const columns: string[] = [];
  const prefixLengths: Array<number | null> = [];
  text.split(',').map((part) => part.trim()).filter(Boolean).forEach((part) => {
    const match = part.match(/^([^(]+?)(?:\((\d+)\))?$/);
    if (!match) return;
    columns.push(match[1].trim());
    prefixLengths.push(match[2] ? Number(match[2]) : null);
  });
  return { columns, prefixLengths };
}

export function parseColumnList(text: string): string[] {
  return text.split(',').map((part) => part.trim()).filter(Boolean);
}

export function adoptStructure(structure: TableStructure): TableDraft {
  return {
    name: structure.name,
    comment: structure.comment ?? '',
    engine: structure.engine ?? '',
    charset: structure.charset ?? '',
    collation: structure.collation ?? '',
    columns: (structure.columns ?? []).map((column) => ({
      ...column,
      clientId: newStructureClientId(),
      comment: column.comment ?? '',
      originalName: column.originalName ?? column.name,
    })),
    primaryKey: structure.primaryKey
      ? { name: structure.primaryKey.name, columns: [...structure.primaryKey.columns] }
      : null,
    indexes: (structure.indexes ?? []).map((index) => toIndexDraft(index)),
    foreignKeys: (structure.foreignKeys ?? []).map((foreignKey) => toForeignKeyDraft(foreignKey)),
    checks: (structure.checks ?? []).map((check) => ({ ...check, clientId: newStructureClientId() })),
  };
}

export function toStructurePayload(draft: TableDraft): TableStructure {
  return {
    name: draft.name,
    comment: draft.comment,
    engine: draft.engine || null,
    charset: draft.charset || null,
    collation: draft.collation || null,
    columns: draft.columns.map(({ clientId: _clientId, ...column }) => column),
    primaryKey: draft.primaryKey && draft.primaryKey.columns.length > 0
      ? { name: draft.primaryKey.name, columns: [...draft.primaryKey.columns] }
      : null,
    indexes: draft.indexes.map((index) => {
      const parsed = parseIndexColumns(index.columnText);
      return {
        name: index.name,
        originalName: index.originalName,
        unique: index.unique,
        type: index.type,
        columns: parsed.columns,
        prefixLengths: parsed.prefixLengths,
      };
    }),
    foreignKeys: draft.foreignKeys.map((foreignKey) => ({
      name: foreignKey.name,
      originalName: foreignKey.originalName,
      columns: parseColumnList(foreignKey.columnsText),
      referencedSchema: foreignKey.referencedSchema || null,
      referencedTable: foreignKey.referencedTable,
      referencedColumns: parseColumnList(foreignKey.referencedColumnsText),
      onUpdate: foreignKey.onUpdate,
      onDelete: foreignKey.onDelete,
    })),
    checks: draft.checks.map(({ clientId: _clientId, ...check }) => check),
  };
}

export function structuresEqual(left: TableStructure, right: TableStructure): boolean {
  return JSON.stringify(canonicalize(left)) === JSON.stringify(canonicalize(right));
}

export function applyColumnType(column: ColumnDraft, columnType: string): ColumnDraft {
  const trimmed = columnType.trim();
  const unsigned = /\sunsigned$/i.test(trimmed);
  const match = trimmed.match(/^([A-Za-z][A-Za-z0-9_]*)(?:\((\d+)(?:\s*,\s*(\d+))?\))?/);
  return {
    ...column,
    columnType: trimmed,
    typeName: match?.[1]?.toLowerCase() ?? column.typeName,
    length: match?.[2] ? Number(match[2]) : null,
    scale: match?.[3] ? Number(match[3]) : null,
    unsigned,
  };
}

export function applyColumnLength(column: ColumnDraft, length: number | null): ColumnDraft {
  const base = column.columnType.replace(/\s+unsigned$/i, '').replace(/\([^)]*\)$/, '');
  let next = base;
  if (length != null && length > 0) {
    next = column.scale != null && /^(decimal|numeric)$/i.test(base)
      ? `${base}(${length},${column.scale})`
      : `${base}(${length})`;
  }
  if (column.unsigned) next += ' unsigned';
  return applyColumnType({ ...column, length }, next);
}

export function emptyColumn(): ColumnDraft {
  return applyColumnType({
    clientId: newStructureClientId(),
    name: '',
    originalName: null,
    typeName: 'varchar',
    columnType: 'varchar(255)',
    length: 255,
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
  }, 'varchar(255)');
}

export function emptyIndex(): IndexDraft {
  return {
    clientId: newStructureClientId(),
    name: '',
    originalName: null,
    unique: false,
    type: 'BTREE',
    columns: [],
    prefixLengths: [],
    columnText: '',
  };
}

export function emptyForeignKey(): ForeignKeyDraft {
  return {
    clientId: newStructureClientId(),
    name: '',
    originalName: null,
    columns: [],
    referencedSchema: null,
    referencedTable: '',
    referencedColumns: [],
    onUpdate: 'RESTRICT',
    onDelete: 'RESTRICT',
    columnsText: '',
    referencedColumnsText: '',
  };
}

export function emptyCheck(): StructureCheck & { clientId: string } {
  return { clientId: newStructureClientId(), name: '', originalName: null, expression: '' };
}

export function togglePrimaryKey(primaryKey: StructurePrimaryKey | null, columnName: string, checked: boolean): StructurePrimaryKey | null {
  const current = primaryKey?.columns ?? [];
  const next = checked
    ? current.includes(columnName) ? current : [...current, columnName]
    : current.filter((name) => name !== columnName);
  if (next.length === 0) return null;
  return { name: primaryKey?.name ?? 'PRIMARY', columns: next };
}

function toIndexDraft(index: StructureIndex): IndexDraft {
  return {
    ...index,
    clientId: newStructureClientId(),
    columnText: formatIndexColumns(index.columns ?? [], index.prefixLengths),
  };
}

function toForeignKeyDraft(foreignKey: StructureForeignKey): ForeignKeyDraft {
  return {
    ...foreignKey,
    clientId: newStructureClientId(),
    columnsText: (foreignKey.columns ?? []).join(', '),
    referencedColumnsText: (foreignKey.referencedColumns ?? []).join(', '),
  };
}

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === 'object') {
    return Object.keys(value as Record<string, unknown>).sort().reduce<Record<string, unknown>>((acc, key) => {
      acc[key] = canonicalize((value as Record<string, unknown>)[key]);
      return acc;
    }, {});
  }
  return value;
}
