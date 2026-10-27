import type { ErCardinality, ErColumn, ErDiagram, ErRelation, ErTable } from '../../services/table.service';

/** Plain snapshot of the diagram currently shown. Later text exports share this input. */
export interface ErDiagramDocumentColumn {
  name: string;
  typeName: string;
  comment: string;
  primaryKey: boolean;
}

export interface ErDiagramDocumentTable {
  name: string;
  catalog: string | null;
  schema: string | null;
  external: boolean;
  comment: string;
  columns: ErDiagramDocumentColumn[];
}

export interface ErDiagramDocumentRelation {
  name: string;
  fromCatalog: string | null;
  fromSchema: string | null;
  fromTable: string;
  fromColumn: string;
  fromColumns: string[];
  toCatalog: string | null;
  toSchema: string | null;
  toTable: string;
  toColumn: string;
  toColumns: string[];
  cardinality: ErCardinality;
  viaCatalog: string | null;
  viaSchema: string | null;
  viaTable: string | null;
}

export interface ErDiagramDocument {
  tables: ErDiagramDocumentTable[];
  relations: ErDiagramDocumentRelation[];
  truncated: boolean;
}

function orNull(value: string | null | undefined): string | null {
  return value ?? null;
}

function columnDocument(column: ErColumn): ErDiagramDocumentColumn {
  return {
    name: column.name,
    typeName: column.typeName,
    comment: column.comment ?? '',
    primaryKey: column.primaryKey,
  };
}

function tableDocument(table: ErTable): ErDiagramDocumentTable {
  return {
    name: table.name,
    catalog: orNull(table.catalog),
    schema: orNull(table.schema),
    external: table.external === true,
    comment: table.comment ?? '',
    columns: table.columns.map(columnDocument),
  };
}

function relationDocument(relation: ErRelation): ErDiagramDocumentRelation {
  return {
    name: relation.name,
    fromCatalog: orNull(relation.fromCatalog),
    fromSchema: orNull(relation.fromSchema),
    fromTable: relation.fromTable,
    fromColumn: relation.fromColumn,
    fromColumns: [...relation.fromColumns],
    toCatalog: orNull(relation.toCatalog),
    toSchema: orNull(relation.toSchema),
    toTable: relation.toTable,
    toColumn: relation.toColumn,
    toColumns: [...relation.toColumns],
    cardinality: relation.cardinality,
    viaCatalog: orNull(relation.viaCatalog),
    viaSchema: orNull(relation.viaSchema),
    viaTable: orNull(relation.viaTable),
  };
}

/** Copies the shown tables, columns, and relations. Layout and external truncation stay out. */
export function toErDiagramDocument(shown: ErDiagram): ErDiagramDocument {
  return {
    tables: shown.tables.map(tableDocument),
    relations: shown.relations.map(relationDocument),
    truncated: shown.truncated,
  };
}

export function renderErDiagramJson(document: ErDiagramDocument): string {
  return JSON.stringify(document, null, 2);
}
