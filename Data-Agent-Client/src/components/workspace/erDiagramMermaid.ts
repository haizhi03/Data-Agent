import type { ErDiagramDocument, ErDiagramDocumentColumn, ErDiagramDocumentRelation, ErDiagramDocumentTable } from './erDiagramDocument';
import { endMarks, relationFromId, relationToId, sideColumns, tableId, viaTableName, type EndMark } from './erDiagramRelations';

const SAFE_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

function mermaidType(typeName: string) {
  const tightened = typeName
    .trim()
    .replace(/\(\s+/g, '(')
    .replace(/\s+\)/g, ')')
    .replace(/,\s+/g, ',')
    .replace(/\s+,/g, ',');
  return tightened.replace(/\s+/g, '_') || 'string';
}

function mermaidToken(value: string) {
  const cleaned = value.replace(/[\r\n]+/g, ' ').replace(/[ \t]+/g, ' ').trim();
  if (SAFE_NAME.test(cleaned)) return cleaned;
  return `"${cleaned.replace(/"/g, '#quot;')}"`;
}

function scopePrefix(table: ErDiagramDocumentTable) {
  return table.schema || table.catalog || '';
}

function qualifiedName(table: ErDiagramDocumentTable) {
  const parts = [table.catalog, table.schema, table.name].filter((part): part is string => !!part);
  return parts.join('.');
}

/** Bare names stay readable. A repeated name is qualified so two entities do not collide. */
function entityNames(tables: ErDiagramDocumentTable[]) {
  const bareCount = new Map<string, number>();
  tables.forEach((table) => {
    bareCount.set(table.name, (bareCount.get(table.name) ?? 0) + 1);
  });
  const raw = tables.map((table) => {
    if ((bareCount.get(table.name) ?? 0) < 2) return table.name;
    const scope = scopePrefix(table);
    return scope ? `${scope}.${table.name}` : table.name;
  });
  const rawCount = new Map<string, number>();
  raw.forEach((name) => rawCount.set(name, (rawCount.get(name) ?? 0) + 1));
  const names = new Map<string, string>();
  tables.forEach((table, index) => {
    const text = (rawCount.get(raw[index]) ?? 0) > 1 ? qualifiedName(table) : raw[index];
    names.set(tableId(table), mermaidToken(text));
  });
  return names;
}

function crow(mark: EndMark, side: 'left' | 'right') {
  if (mark === 'one') return '||';
  return side === 'left' ? '}o' : 'o{';
}

function sameColumns(left: string[], right: string[]) {
  return left.length === right.length && left.every((column, index) => column === right[index]);
}

function relationLabel(relation: ErDiagramDocumentRelation) {
  const fromColumns = sideColumns(relation, 'from');
  const toColumns = sideColumns(relation, 'to');
  const fromText = fromColumns.join(', ');
  const toText = toColumns.join(', ');
  let label = '';
  if (sameColumns(fromColumns, toColumns) || !toText) label = fromText;
  else if (!fromText) label = toText;
  else label = `${fromText} → ${toText}`;
  const via = viaTableName(relation);
  if (via) label = label ? `${label} via ${via}` : via;
  return label;
}

function relationLine(relation: ErDiagramDocumentRelation, names: Map<string, string>) {
  const fromName = names.get(relationFromId(relation));
  const toName = names.get(relationToId(relation));
  if (!fromName || !toName) return '';
  const marks = endMarks(relation);
  const swap = marks.from === 'many' && marks.to === 'one';
  const left = swap ? toName : fromName;
  const right = swap ? fromName : toName;
  const leftMark = crow(swap ? marks.to : marks.from, 'left');
  const rightMark = crow(swap ? marks.from : marks.to, 'right');
  const label = relationLabel(relation);
  const suffix = label ? ` : ${mermaidToken(label)}` : '';
  return `    ${left} ${leftMark}--${rightMark} ${right}${suffix}`;
}

function attributeLine(column: ErDiagramDocumentColumn) {
  const key = column.primaryKey ? ' PK' : '';
  return `        ${mermaidType(column.typeName)} ${mermaidToken(column.name)}${key}`;
}

function entityBlock(table: ErDiagramDocumentTable, names: Map<string, string>) {
  const name = names.get(tableId(table)) ?? mermaidToken(table.name);
  return [`    ${name} {`, ...table.columns.map(attributeLine), '    }'];
}

/** erDiagram text for a README fence. Comments stay out; cardinality uses crow's foot marks. */
export function renderErDiagramMermaid(document: ErDiagramDocument) {
  const names = entityNames(document.tables);
  const relations = document.relations.flatMap((relation) => {
    const line = relationLine(relation, names);
    return line ? [line] : [];
  });
  const tables = document.tables.flatMap((table) => entityBlock(table, names));
  return ['erDiagram', ...relations, ...tables].join('\n') + '\n';
}
