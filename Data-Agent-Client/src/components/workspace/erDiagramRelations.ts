import type { ErDiagram, ErRelation } from '../../services/table.service';

export type EndMark = 'one' | 'many';

export interface ColumnFocus {
  tableId: string;
  column: string;
}

export function tableId(table: { catalog?: string | null; schema?: string | null; name: string }) {
  return `${table.catalog ?? ''}\0${table.schema ?? ''}\0${table.name}`;
}

export function columnKey(table: string, column: string) {
  return `${table}\0${column}`;
}

export function relationFromId(relation: ErRelation) {
  return tableId({ catalog: relation.fromCatalog, schema: relation.fromSchema, name: relation.fromTable });
}

export function relationToId(relation: ErRelation) {
  return tableId({ catalog: relation.toCatalog, schema: relation.toSchema, name: relation.toTable });
}

export function isCrossRelation(relation: ErRelation) {
  return `${relation.fromCatalog ?? ''}\0${relation.fromSchema ?? ''}`
    !== `${relation.toCatalog ?? ''}\0${relation.toSchema ?? ''}`;
}

export function sideColumns(relation: ErRelation, side: 'from' | 'to') {
  const columns = side === 'from' ? relation.fromColumns : relation.toColumns;
  const fallback = side === 'from' ? relation.fromColumn : relation.toColumn;
  if (columns && columns.length > 0) return columns;
  return fallback ? [fallback] : [];
}

export function relationTouches(relation: ErRelation, focus: ColumnFocus) {
  if (relationFromId(relation) === focus.tableId && sideColumns(relation, 'from').includes(focus.column)) {
    return true;
  }
  return relationToId(relation) === focus.tableId && sideColumns(relation, 'to').includes(focus.column);
}

/** The hovered column anchors its own side. The other side uses the column at the same position. */
export function anchorColumns(relation: ErRelation, focus: ColumnFocus) {
  const fromColumns = sideColumns(relation, 'from');
  const toColumns = sideColumns(relation, 'to');
  const fromFallback = fromColumns[0] ?? '';
  const toFallback = toColumns[0] ?? '';
  if (relationFromId(relation) === focus.tableId) {
    const index = fromColumns.indexOf(focus.column);
    if (index >= 0) {
      return { from: fromColumns[index], to: toColumns[index] ?? toFallback };
    }
  }
  if (relationToId(relation) === focus.tableId) {
    const index = toColumns.indexOf(focus.column);
    if (index >= 0) {
      return { from: fromColumns[index] ?? fromFallback, to: toColumns[index] };
    }
  }
  return { from: fromFallback, to: toFallback };
}

/**
 * The foreign-key side is many unless the foreign key itself is unique.
 * A many-to-many link is many at both referenced tables.
 */
export function endMarks(relation: ErRelation): { from: EndMark; to: EndMark } {
  if (relation.cardinality === 'MANY_TO_MANY') return { from: 'many', to: 'many' };
  if (relation.cardinality === 'ONE_TO_ONE') return { from: 'one', to: 'one' };
  return { from: 'many', to: 'one' };
}

export function cardinalityLabel(relation: ErRelation) {
  if (relation.cardinality === 'ONE_TO_ONE') return '1:1';
  if (relation.cardinality === 'MANY_TO_MANY') return 'N:M';
  return '1:N';
}

function columnList(columns: string[]) {
  return columns.length === 1 ? columns[0] : `(${columns.join(', ')})`;
}

function formatEnd(relation: ErRelation, side: 'from' | 'to', cross: boolean) {
  const table = side === 'from' ? relation.fromTable : relation.toTable;
  const columns = columnList(sideColumns(relation, side));
  if (!cross) return `${table}.${columns}`;
  const scope = (side === 'from' ? relation.fromSchema || relation.fromCatalog : relation.toSchema || relation.toCatalog) || '';
  return scope ? `${scope}.${table}.${columns}` : `${table}.${columns}`;
}

export function relationMapping(relation: ErRelation) {
  const arrow = relation.cardinality === 'MANY_TO_MANY' ? '↔' : '→';
  const cross = isCrossRelation(relation);
  return `${formatEnd(relation, 'from', cross)} ${arrow} ${formatEnd(relation, 'to', cross)}`;
}

export function viaTableName(relation: ErRelation) {
  if (relation.cardinality !== 'MANY_TO_MANY' || !relation.viaTable) return '';
  const scope = relation.viaSchema || relation.viaCatalog || '';
  return scope ? `${scope}.${relation.viaTable}` : relation.viaTable;
}

export function junctionTableId(relation: ErRelation) {
  if (relation.cardinality !== 'MANY_TO_MANY' || !relation.viaTable) return '';
  return tableId({ catalog: relation.viaCatalog, schema: relation.viaSchema, name: relation.viaTable });
}

/** Tables whose names match the query. Null when the query is blank, so the diagram stays unmarked. */
export function matchingTableIds(diagram: ErDiagram, query: string, exact = false): Set<string> | null {
  const needles = query.split(',').map((part) => part.trim().toLowerCase()).filter(Boolean);
  if (needles.length === 0) return null;
  const kept = new Set<string>();
  diagram.tables.forEach((table) => {
    if (tableNameMatches(table, needles, exact)) kept.add(tableId(table));
  });
  return kept;
}

function tableNameMatches(
  table: { catalog?: string | null; schema?: string | null; name: string },
  needles: string[],
  exact: boolean,
) {
  const name = table.name.toLowerCase();
  const scope = (table.schema || table.catalog || '').toLowerCase();
  const qualified = scope ? `${scope}.${name}` : name;
  return needles.some((needle) => {
    if (exact) return name === needle || qualified === needle;
    if (name.includes(needle)) return true;
    if (needle.includes('.') && qualified.includes(needle)) return true;
    return scope.length > 0 && scope === needle;
  });
}

/** Keeps tables whose name matches, and lines whose two ends are both still shown. */
export function filterTablesByName(diagram: ErDiagram, query: string, exact = false): ErDiagram {
  const kept = matchingTableIds(diagram, query, exact);
  if (!kept) return diagram;
  return {
    ...diagram,
    tables: diagram.tables.filter((table) => kept.has(tableId(table))),
    relations: diagram.relations.filter((relation) => (
      kept.has(relationFromId(relation)) && kept.has(relationToId(relation))
    )),
  };
}

/** Drops junction cards and their foreign keys. The many-to-many link between the two ends stays. */
export function collapseJunctions(diagram: ErDiagram, enabled: boolean): ErDiagram {
  if (!enabled) return diagram;
  const junctions = new Set<string>();
  diagram.relations.forEach((relation) => {
    const id = junctionTableId(relation);
    if (id) junctions.add(id);
  });
  if (junctions.size === 0) return diagram;
  return {
    ...diagram,
    tables: diagram.tables.filter((table) => !junctions.has(tableId(table))),
    relations: diagram.relations.filter((relation) => {
      if (relation.cardinality === 'MANY_TO_MANY') return true;
      return !junctions.has(relationFromId(relation)) && !junctions.has(relationToId(relation));
    }),
  };
}
