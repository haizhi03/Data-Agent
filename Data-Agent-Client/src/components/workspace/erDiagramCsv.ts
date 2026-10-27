import { I18N_KEYS } from '../../constants/i18nKeys';
import type { ErDiagramDocument, ErDiagramDocumentRelation, ErDiagramDocumentTable } from './erDiagramDocument';
import { endMarks, isCrossRelation, sideColumns, viaTableName, type EndMark } from './erDiagramRelations';

export interface ErDiagramCsvTableHeaders {
  schema: string;
  table: string;
  tableComment: string;
  column: string;
  type: string;
  primaryKey: string;
  columnComment: string;
  external: string;
}

export interface ErDiagramCsvRelationHeaders {
  name: string;
  fromSchema: string;
  fromTable: string;
  fromColumns: string;
  toSchema: string;
  toTable: string;
  toColumns: string;
  cardinality: string;
  viaTable: string;
  crossSchema: string;
}

export interface ErDiagramCsvHeaders {
  tables: ErDiagramCsvTableHeaders;
  relations: ErDiagramCsvRelationHeaders;
}

/** Column titles follow the active language. Cell values stay language-neutral. */
export function erDiagramCsvHeaders(translate: (key: string) => string): ErDiagramCsvHeaders {
  const label = I18N_KEYS.EXPLORER;
  return {
    tables: {
      schema: translate(label.ER_DIAGRAM_CSV_SCHEMA),
      table: translate(label.ER_DIAGRAM_CSV_TABLE),
      tableComment: translate(label.ER_DIAGRAM_CSV_TABLE_COMMENT),
      column: translate(label.ER_DIAGRAM_CSV_COLUMN),
      type: translate(label.ER_DIAGRAM_CSV_TYPE),
      primaryKey: translate(label.ER_DIAGRAM_CSV_PRIMARY_KEY),
      columnComment: translate(label.ER_DIAGRAM_CSV_COLUMN_COMMENT),
      external: translate(label.ER_DIAGRAM_CSV_EXTERNAL),
    },
    relations: {
      name: translate(label.ER_DIAGRAM_CSV_CONSTRAINT),
      fromSchema: translate(label.ER_DIAGRAM_CSV_FROM_SCHEMA),
      fromTable: translate(label.ER_DIAGRAM_CSV_FROM_TABLE),
      fromColumns: translate(label.ER_DIAGRAM_CSV_FROM_COLUMNS),
      toSchema: translate(label.ER_DIAGRAM_CSV_TO_SCHEMA),
      toTable: translate(label.ER_DIAGRAM_CSV_TO_TABLE),
      toColumns: translate(label.ER_DIAGRAM_CSV_TO_COLUMNS),
      cardinality: translate(label.ER_DIAGRAM_CSV_CARDINALITY),
      viaTable: translate(label.ER_DIAGRAM_CSV_VIA_TABLE),
      crossSchema: translate(label.ER_DIAGRAM_CSV_CROSS_SCHEMA),
    },
  };
}

function scopeName(schema: string | null, catalog: string | null) {
  return schema || catalog || '';
}

function boolCell(value: boolean) {
  return value ? 'true' : 'false';
}

/** From-to direction: a foreign key is N:1. Both ends many is N:M, matching the diagram legend. */
function cardinalityCell(relation: ErDiagramDocumentRelation) {
  const marks = endMarks(relation);
  if (marks.from === 'many' && marks.to === 'many') return 'N:M';
  const token = (mark: EndMark) => (mark === 'many' ? 'N' : '1');
  return `${token(marks.from)}:${token(marks.to)}`;
}

function csvField(value: string) {
  if (value.includes(',') || value.includes('"') || value.includes('\n') || value.includes('\r')) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

function csvText(header: string[], rows: string[][]) {
  const lines = [header, ...rows].map((cells) => cells.map(csvField).join(','));
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

function tableFieldRows(table: ErDiagramDocumentTable): string[][] {
  const lead = [scopeName(table.schema, table.catalog), table.name, table.comment];
  const external = boolCell(table.external);
  if (table.columns.length === 0) return [[...lead, '', '', '', '', external]];
  return table.columns.map((column) => [
    ...lead,
    column.name,
    column.typeName,
    boolCell(column.primaryKey),
    column.comment,
    external,
  ]);
}

function relationRow(relation: ErDiagramDocumentRelation): string[] {
  return [
    relation.name,
    scopeName(relation.fromSchema, relation.fromCatalog),
    relation.fromTable,
    sideColumns(relation, 'from').join(', '),
    scopeName(relation.toSchema, relation.toCatalog),
    relation.toTable,
    sideColumns(relation, 'to').join(', '),
    cardinalityCell(relation),
    viaTableName(relation),
    boolCell(isCrossRelation(relation)),
  ];
}

/** UTF-8 text with a BOM so Excel opens Chinese. One row per column, one row per relation. */
export function renderErDiagramCsv(document: ErDiagramDocument, headers: ErDiagramCsvHeaders) {
  return {
    tables: csvText([
      headers.tables.schema,
      headers.tables.table,
      headers.tables.tableComment,
      headers.tables.column,
      headers.tables.type,
      headers.tables.primaryKey,
      headers.tables.columnComment,
      headers.tables.external,
    ], document.tables.flatMap(tableFieldRows)),
    relations: csvText([
      headers.relations.name,
      headers.relations.fromSchema,
      headers.relations.fromTable,
      headers.relations.fromColumns,
      headers.relations.toSchema,
      headers.relations.toTable,
      headers.relations.toColumns,
      headers.relations.cardinality,
      headers.relations.viaTable,
      headers.relations.crossSchema,
    ], document.relations.map(relationRow)),
  };
}
