import type { ErRelation, ErTable } from '../../services/table.service';
import { relationFromId, relationMapping, relationToId, tableId } from './erDiagramRelations';

/** Above this, the selection still works, and the bar warns that the answer will spread out. */
export const ER_FOCUS_SPREAD_AT = 8;

const STATUS_HOP_LIMIT = 3;
const PROMPT_COLUMN_LIMIT = 40;

export interface ErFocusColumn {
  name: string;
  typeName: string;
  primaryKey: boolean;
}

export interface ErFocusTablePayload {
  name: string;
  catalog?: string | null;
  schema?: string | null;
  columns: ErFocusColumn[];
}

export interface ErFocusRelationPayload {
  fromCatalog?: string | null;
  fromSchema?: string | null;
  fromTable: string;
  fromColumns: string[];
  toCatalog?: string | null;
  toSchema?: string | null;
  toTable: string;
  toColumns: string[];
  cardinality: string;
  viaCatalog?: string | null;
  viaSchema?: string | null;
  viaTable?: string | null;
}

export interface ErFocusSplit {
  inside: ErRelation[];
  outside: ErRelation[];
}

export interface ErFocusHop {
  outside: string;
  link: string;
}

export interface ErQueryFocusPayload {
  connectionId: number;
  connectionName: string;
  catalogName?: string;
  schemaName?: string;
  tables: ErFocusTablePayload[];
  insideRelations: ErFocusRelationPayload[];
  outsideRelations: ErFocusRelationPayload[];
}

export function splitFocusRelations(relations: ErRelation[], selected: ReadonlySet<string>): ErFocusSplit {
  const inside: ErRelation[] = [];
  const outside: ErRelation[] = [];
  relations.forEach((relation) => {
    const fromIn = selected.has(relationFromId(relation));
    const toIn = selected.has(relationToId(relation));
    if (fromIn && toIn) inside.push(relation);
    else if (fromIn || toIn) outside.push(relation);
  });
  return { inside, outside };
}

export function focusHops(outside: ErRelation[], selected: ReadonlySet<string>): ErFocusHop[] {
  return outside.map((relation) => {
    const fromIn = selected.has(relationFromId(relation));
    return {
      outside: fromIn ? relation.toTable : relation.fromTable,
      link: relationMapping(relation),
    };
  });
}

export function visibleFocusHops(hops: ErFocusHop[]) {
  return {
    shown: hops.slice(0, STATUS_HOP_LIMIT),
    extra: Math.max(0, hops.length - STATUS_HOP_LIMIT),
  };
}

export function focusInvolvedTableIds(selected: ReadonlySet<string>, split: ErFocusSplit) {
  const ids = new Set(selected);
  [...split.inside, ...split.outside].forEach((relation) => {
    ids.add(relationFromId(relation));
    ids.add(relationToId(relation));
  });
  return ids;
}

export function toFocusPayload(
  tables: ErTable[],
  split: ErFocusSplit,
  scope: { connectionId: number; connectionName: string; catalogName?: string | null; schemaName?: string | null },
): ErQueryFocusPayload {
  return {
    connectionId: scope.connectionId,
    connectionName: scope.connectionName,
    catalogName: scope.catalogName || undefined,
    schemaName: scope.schemaName || undefined,
    tables: tables.map((table) => ({
      name: table.name,
      catalog: table.catalog,
      schema: table.schema,
      columns: table.columns.slice(0, PROMPT_COLUMN_LIMIT).map((column) => ({
        name: column.name,
        typeName: column.typeName,
        primaryKey: column.primaryKey,
      })),
    })),
    insideRelations: split.inside.map(toRelationPayload),
    outsideRelations: split.outside.map(toRelationPayload),
  };
}

export function relationFocusKey(relation: ErRelation) {
  const fromColumns = relation.fromColumns?.length ? relation.fromColumns : [relation.fromColumn];
  const toColumns = relation.toColumns?.length ? relation.toColumns : [relation.toColumn];
  return [
    relation.name,
    relation.fromTable,
    fromColumns.join(','),
    relation.toTable,
    toColumns.join(','),
    relation.viaTable ?? '',
  ].join('|');
}

export function selectedTableId(table: ErTable) {
  return tableId(table);
}

function toRelationPayload(relation: ErRelation): ErFocusRelationPayload {
  return {
    fromCatalog: relation.fromCatalog,
    fromSchema: relation.fromSchema,
    fromTable: relation.fromTable,
    fromColumns: relation.fromColumns?.length ? relation.fromColumns : [relation.fromColumn],
    toCatalog: relation.toCatalog,
    toSchema: relation.toSchema,
    toTable: relation.toTable,
    toColumns: relation.toColumns?.length ? relation.toColumns : [relation.toColumn],
    cardinality: relation.cardinality,
    viaCatalog: relation.viaCatalog,
    viaSchema: relation.viaSchema,
    viaTable: relation.viaTable,
  };
}
