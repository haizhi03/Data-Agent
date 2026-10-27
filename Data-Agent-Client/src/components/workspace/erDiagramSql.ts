import type { IdentifierQuoteStyle } from '../../types/dbType';
import type { ErDiagramDocument, ErDiagramDocumentRelation, ErDiagramDocumentTable } from './erDiagramDocument';
import { relationFromId, relationToId, sideColumns, tableId } from './erDiagramRelations';

export type ErSqlDialect = 'mysql' | 'comment_on';

export interface ErDiagramSqlNotes {
  incomplete: string;
  omittedColumn: (table: string, column: string) => string;
  omittedTable: (table: string) => string;
  omittedForeignKey: (name: string) => string;
}

export interface ErDiagramSqlOptions {
  quote: IdentifierQuoteStyle;
  dialect: ErSqlDialect;
  incomplete: boolean;
  notes: ErDiagramSqlNotes;
}

interface EmittedTable {
  qualified: string;
  columns: Set<string>;
}

/** MySQL uses inline comments. Every other connection, including DM, uses COMMENT ON. */
export function erDiagramSqlSettings(dbType: string | undefined, quoteStyle?: IdentifierQuoteStyle) {
  const dialect: ErSqlDialect = (dbType ?? '').trim().toLowerCase() === 'mysql' ? 'mysql' : 'comment_on';
  const quote = quoteStyle ?? (dialect === 'mysql' ? 'backtick' : 'double_quote');
  return { quote, dialect };
}

export function erDiagramSqlIncomplete(diagram: {
  truncated: boolean;
  externalTruncated?: boolean;
  tables: Array<{ external?: boolean }>;
}) {
  return diagram.truncated
    || diagram.externalTruncated === true
    || diagram.tables.some((table) => table.external === true);
}

function quoteIdent(name: string, quote: IdentifierQuoteStyle) {
  if (quote === 'none') return name;
  if (quote === 'backtick') return `\`${name.replace(/`/g, '``')}\``;
  return `"${name.replace(/"/g, '""')}"`;
}

function rawQualified(table: { schema: string | null; catalog: string | null; name: string }) {
  const scope = table.schema || table.catalog || '';
  return scope ? `${scope}.${table.name}` : table.name;
}

function qualifiedTable(
  table: { schema: string | null; catalog: string | null; name: string },
  quote: IdentifierQuoteStyle,
) {
  const scope = table.schema || table.catalog || '';
  const name = quoteIdent(table.name, quote);
  return scope ? `${quoteIdent(scope, quote)}.${name}` : name;
}

function sqlString(value: string, dialect: ErSqlDialect) {
  const escaped = dialect === 'mysql'
    ? value.replace(/\\/g, '\\\\').replace(/'/g, "''")
    : value.replace(/'/g, "''");
  return `'${escaped}'`;
}

function columnList(columns: string[], quote: IdentifierQuoteStyle) {
  return columns.map((column) => quoteIdent(column, quote)).join(', ');
}

function buildTable(table: ErDiagramDocumentTable, options: ErDiagramSqlOptions) {
  const emittedColumns = table.columns.filter((column) => column.name.trim() && column.typeName.trim());
  const omitted = table.columns.filter((column) => column.name.trim() && !column.typeName.trim());
  const label = rawQualified(table);
  const emittedNames = new Set(emittedColumns.map((column) => column.name));
  const primaryKeyBroken = table.columns.some((column) => column.primaryKey && !emittedNames.has(column.name));
  const commentLines = omitted.map((column) => `-- ${options.notes.omittedColumn(label, column.name)}`);
  if (emittedColumns.length === 0) {
    return {
      text: [`-- ${options.notes.omittedTable(label)}`, ...commentLines].join('\n'),
      emitted: null,
    };
  }
  const qualified = qualifiedTable(table, options.quote);
  const lines = emittedColumns.map((column) => {
    const comment = options.dialect === 'mysql' && column.comment
      ? ` COMMENT ${sqlString(column.comment, options.dialect)}`
      : '';
    return `  ${quoteIdent(column.name, options.quote)} ${column.typeName}${comment}`;
  });
  if (!primaryKeyBroken) {
    const primaryKey = emittedColumns.filter((column) => column.primaryKey).map((column) => column.name);
    if (primaryKey.length > 0) lines.push(`  PRIMARY KEY (${columnList(primaryKey, options.quote)})`);
  }
  const tableComment = options.dialect === 'mysql' && table.comment
    ? ` COMMENT=${sqlString(table.comment, options.dialect)}`
    : '';
  let statement = `CREATE TABLE ${qualified} (\n${lines.join(',\n')}\n)${tableComment};`;
  if (options.dialect === 'comment_on') {
    const comments: string[] = [];
    if (table.comment) comments.push(`COMMENT ON TABLE ${qualified} IS ${sqlString(table.comment, options.dialect)};`);
    emittedColumns.forEach((column) => {
      if (!column.comment) return;
      const quoted = quoteIdent(column.name, options.quote);
      comments.push(`COMMENT ON COLUMN ${qualified}.${quoted} IS ${sqlString(column.comment, options.dialect)};`);
    });
    if (comments.length > 0) statement = `${statement}\n${comments.join('\n')}`;
  }
  return {
    text: [...commentLines, statement].join('\n'),
    emitted: { qualified, columns: emittedNames },
  };
}

function buildForeignKey(
  relation: ErDiagramDocumentRelation,
  tables: Map<string, EmittedTable>,
  options: ErDiagramSqlOptions,
) {
  if (relation.cardinality === 'MANY_TO_MANY') return '';
  const from = tables.get(relationFromId(relation));
  const to = tables.get(relationToId(relation));
  const fromColumns = sideColumns(relation, 'from');
  const toColumns = sideColumns(relation, 'to');
  const name = relation.name.trim();
  const columnsReady = !!from && !!to
    && fromColumns.length > 0
    && toColumns.length > 0
    && fromColumns.every((column) => from.columns.has(column))
    && toColumns.every((column) => to.columns.has(column));
  if (!name || !columnsReady || !from || !to) {
    const label = name || `${relation.fromTable} -> ${relation.toTable}`;
    return `-- ${options.notes.omittedForeignKey(label)}`;
  }
  return `ALTER TABLE ${from.qualified} ADD CONSTRAINT ${quoteIdent(name, options.quote)} `
    + `FOREIGN KEY (${columnList(fromColumns, options.quote)}) `
    + `REFERENCES ${to.qualified} (${columnList(toColumns, options.quote)});`;
}

/** Downloadable DDL for the shown diagram. Nullable and default values are never invented. */
export function renderErDiagramSql(document: ErDiagramDocument, options: ErDiagramSqlOptions) {
  const blocks: string[] = [];
  if (options.incomplete) blocks.push(`-- ${options.notes.incomplete}`);
  const emitted = new Map<string, EmittedTable>();
  document.tables.forEach((table) => {
    const built = buildTable(table, options);
    blocks.push(built.text);
    if (built.emitted) emitted.set(tableId(table), built.emitted);
  });
  document.relations.forEach((relation) => {
    const statement = buildForeignKey(relation, emitted, options);
    if (statement) blocks.push(statement);
  });
  if (blocks.length === 0) return '\n';
  return `${blocks.join('\n\n')}\n`;
}
