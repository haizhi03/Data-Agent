import { isDmDbType, quoteCreateTableIdentifier } from './sqlComment';

const COMMON_CREATE_TABLE_TYPES = [
  'INT',
  'BIGINT',
  'VARCHAR(255)',
  'TEXT',
  'DATE',
  'DATETIME',
  'TIMESTAMP',
  'DECIMAL(10,2)',
  'BOOLEAN',
  'FLOAT',
  'DOUBLE',
] as const;

const DM_CREATE_TABLE_TYPES = [
  'INT',
  'BIGINT',
  'VARCHAR(255)',
  'VARCHAR2(255)',
  'TEXT',
  'CLOB',
  'DATE',
  'DATETIME',
  'TIMESTAMP',
  'TIMESTAMP(6) WITH TIME ZONE',
  'DECIMAL(10,2)',
  'NUMBER(10,2)',
  'BOOLEAN',
  'FLOAT',
  'DOUBLE',
  'BLOB',
] as const;

export interface CreateTableIndexDefinition {
  name: string;
  columns: string;
}

export function getCreateTableColumnTypes(dbType?: string): readonly string[] {
  return isDmDbType(dbType) ? DM_CREATE_TABLE_TYPES : COMMON_CREATE_TABLE_TYPES;
}

function quoteIndexColumns(columns: string, dbType?: string): string {
  return columns.trim().split(/\s*,\s*/).map((column) => (
    quoteCreateTableIdentifier(column.trim(), dbType)
  )).join(', ');
}

export function buildInlineIndexDefinition(index: CreateTableIndexDefinition, dbType?: string): string {
  return `  KEY ${quoteCreateTableIdentifier(index.name.trim(), dbType)} (${quoteIndexColumns(index.columns, dbType)})`;
}

export function buildDmCreateIndexStatement(
  schema: string | undefined,
  tableName: string,
  index: CreateTableIndexDefinition,
  dbType?: string,
): string {
  const schemaName = schema?.trim();
  const quotedSchema = schemaName ? `${quoteCreateTableIdentifier(schemaName, dbType)}.` : '';
  const table = `${quotedSchema}${quoteCreateTableIdentifier(tableName.trim(), dbType)}`;
  const indexName = `${quotedSchema}${quoteCreateTableIdentifier(index.name.trim(), dbType)}`;
  return `CREATE INDEX ${indexName} ON ${table} (${quoteIndexColumns(index.columns, dbType)})`;
}
