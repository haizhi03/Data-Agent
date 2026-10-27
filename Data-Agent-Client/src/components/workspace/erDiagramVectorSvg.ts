import type { ErTable } from '../../services/table.service';
import { ER_ROSE, ER_VIOLET, type ErEdgeData } from './erDiagramEdges';
import {
  CARD_WIDTH,
  HEADER_HEIGHT,
  ROW_HEIGHT,
  EXPAND_BAR_HEIGHT,
  MAX_VISIBLE_ROWS,
  cardHeight,
  visibleColumns,
} from './erDiagramLayout';
import { columnKey, tableId, type EndMark } from './erDiagramRelations';

export const ER_SVG_FONT_FAMILY = 'Microsoft YaHei, Segoe UI, sans-serif';
export const ER_EXPORT_PADDING = 48;

export interface DiagramBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export function exportImageSize(bounds: DiagramBounds) {
  const width = Math.ceil(bounds.width + ER_EXPORT_PADDING * 2);
  const height = Math.ceil(bounds.height + ER_EXPORT_PADDING * 2);
  const scale = Math.min(1, 8192 / width, 8192 / height, Math.sqrt(16_000_000 / (width * height)));
  return { width: Math.max(1, Math.floor(width * scale)), height: Math.max(1, Math.floor(height * scale)) };
}

export function includeBounds(bounds: DiagramBounds, other: DiagramBounds): DiagramBounds {
  const x = Math.min(bounds.x, other.x);
  const y = Math.min(bounds.y, other.y);
  return {
    x,
    y,
    width: Math.max(bounds.x + bounds.width, other.x + other.width) - x,
    height: Math.max(bounds.y + bounds.height, other.y + other.height) - y,
  };
}

export interface VectorPoint {
  x: number;
  y: number;
}

export interface VectorColumn {
  name: string;
  typeName: string;
  comment: string;
  primaryKey: boolean;
  linked: boolean;
  highlighted: boolean;
}

export interface VectorBadge {
  text: string;
  color: string;
  background?: string;
}

export interface VectorTable {
  x: number;
  y: number;
  width: number;
  height: number;
  title: string;
  external: boolean;
  badges: VectorBadge[];
  columns: VectorColumn[];
  expandLabel?: string;
}

export interface VectorEdge {
  points: VectorPoint[];
  color: string;
  dashed: boolean;
  opacity: number;
  fromMark: EndMark;
  toMark: EndMark;
}

export interface VectorTheme {
  background: string;
  card: string;
  border: string;
  title: string;
  text: string;
  muted: string;
  type: string;
  key: string;
  linked: string;
  highlight: string;
  externalBorder: string;
}

export interface VectorDiagram {
  tables: VectorTable[];
  edges: VectorEdge[];
  theme: VectorTheme;
}

interface FlowNodeLike {
  position: { x: number; y: number };
  width?: number | null;
  height?: number | null;
  data: { table: ErTable; expanded: boolean };
}

export interface VectorTableLabels {
  linked: ReadonlySet<string>;
  highlighted: ReadonlySet<string>;
  junctions: ReadonlySet<string>;
  external: string;
  junction: string;
  expand: (hidden: number) => string;
  collapse: string;
}

function num(value: number) {
  return String(Math.round(value * 100) / 100);
}

export function escapeXml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function textWidth(text: string, fontSize: number) {
  let width = 0;
  for (const char of text) width += char.charCodeAt(0) > 255 ? fontSize : fontSize * 0.56;
  return width;
}

export function fitText(text: string, fontSize: number, maxWidth: number) {
  if (maxWidth <= 0) return '';
  if (textWidth(text, fontSize) <= maxWidth) return text;
  const ellipsis = '…';
  let kept = '';
  for (const char of text) {
    if (textWidth(kept + char + ellipsis, fontSize) > maxWidth) break;
    kept += char;
  }
  return kept ? kept + ellipsis : ellipsis;
}

export function parseSvgPath(d: string): VectorPoint[] {
  const points: VectorPoint[] = [];
  const pattern = /[ML]\s*([+-]?(?:\d+\.?\d*|\.\d+))\s*,?\s*([+-]?(?:\d+\.?\d*|\.\d+))/gi;
  for (const match of d.matchAll(pattern)) {
    points.push({ x: Number(match[1]), y: Number(match[2]) });
  }
  return points;
}

function tableTitle(table: ErTable) {
  if (!table.external) return table.name;
  const scope = table.schema || table.catalog || '';
  return scope ? `${scope}.${table.name}` : table.name;
}

export function vectorTablesFromNodes(nodes: FlowNodeLike[], labels: VectorTableLabels): VectorTable[] {
  return nodes.map((node) => {
    const table = node.data.table;
    const id = tableId(table);
    const expanded = !!node.data.expanded;
    const columns = visibleColumns(table.columns, labels.linked, labels.highlighted, id, expanded);
    const badges: VectorBadge[] = [];
    if (table.external) badges.push({ text: labels.external, color: '#e11d48' });
    if (labels.junctions.has(id)) badges.push({ text: labels.junction, color: '#7c3aed', background: '#7c3aed' });
    return {
      x: node.position.x,
      y: node.position.y,
      width: node.width ?? CARD_WIDTH,
      height: node.height ?? cardHeight(table.columns.length, expanded),
      title: tableTitle(table),
      external: !!table.external,
      badges,
      columns: columns.map((column) => ({
        name: column.name,
        typeName: column.typeName,
        comment: column.comment,
        primaryKey: column.primaryKey,
        linked: labels.linked.has(columnKey(id, column.name)),
        highlighted: labels.highlighted.has(columnKey(id, column.name)),
      })),
      expandLabel: table.columns.length > MAX_VISIBLE_ROWS
        ? (expanded ? labels.collapse : labels.expand(table.columns.length - columns.length))
        : undefined,
    };
  });
}

export function vectorEdgesFromPaths(
  paths: Array<{ id: string; d: string }>,
  edges: Array<{ id: string; data?: ErEdgeData }>,
): VectorEdge[] {
  const byId = new Map(edges.map((edge) => [edge.id, edge.data]));
  return paths.flatMap((path) => {
    const points = parseSvgPath(path.d);
    if (points.length < 2) return [];
    const data = byId.get(path.id);
    return [{
      points,
      color: data?.manyToMany ? ER_VIOLET : ER_ROSE,
      dashed: !!data?.cross,
      opacity: data?.dimmed ? 0.28 : 1,
      fromMark: data?.fromMark ?? 'many',
      toMark: data?.toMark ?? 'one',
    }];
  });
}

function outward(from: VectorPoint, toward: VectorPoint) {
  const dx = toward.x - from.x;
  const dy = toward.y - from.y;
  const length = Math.hypot(dx, dy) || 1;
  return { x: dx / length, y: dy / length };
}

/** Draws the cardinality mark as geometry. Office SVG import ignores <marker>. */
export function cardinalityMark(mark: EndMark, endpoint: VectorPoint, away: VectorPoint) {
  const unit = outward(endpoint, away);
  const side = { x: -unit.y, y: unit.x };
  const at = (along: number, across: number): VectorPoint => ({
    x: endpoint.x + unit.x * along + side.x * across,
    y: endpoint.y + unit.y * along + side.y * across,
  });
  const line = (a: VectorPoint, b: VectorPoint) => `M ${num(a.x)} ${num(a.y)} L ${num(b.x)} ${num(b.y)}`;
  if (mark === 'one') return [line(at(2, -6), at(2, 6))];
  const base = at(12, 0);
  return [at(0, -6), at(0, 0), at(0, 6)].map((tip) => line(base, tip));
}

function text(x: number, y: number, value: string, fontSize: number, fill: string, weight?: number) {
  const weightAttr = weight ? ` font-weight="${weight}"` : '';
  return `<text x="${num(x)}" y="${num(y)}" fill="${escapeXml(fill)}" font-family="${ER_SVG_FONT_FAMILY}" font-size="${fontSize}"${weightAttr}>${escapeXml(value)}</text>`;
}

function edgeMarkup(edge: VectorEdge) {
  const start = edge.points[0];
  const second = edge.points[1];
  const end = edge.points[edge.points.length - 1];
  const previous = edge.points[edge.points.length - 2];
  const dash = edge.dashed ? ' stroke-dasharray="5 4"' : '';
  const commands = edge.points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${num(point.x)} ${num(point.y)}`).join(' ');
  const marks = [
    ...cardinalityMark(edge.fromMark, start, second),
    ...cardinalityMark(edge.toMark, end, previous),
  ];
  const common = `fill="none" stroke="${escapeXml(edge.color)}" stroke-width="1.6" stroke-linecap="round" stroke-opacity="${edge.opacity}"`;
  return [
    `<path d="${commands}" ${common}${dash}/>`,
    ...marks.map((d) => `<path d="${d}" ${common}/>`),
  ].join('');
}

function tableMarkup(table: VectorTable, theme: VectorTheme) {
  const badgeWidths = table.badges.map((badge) => textWidth(badge.text, 10) + 8);
  const badgeSpace = badgeWidths.reduce((sum, width) => sum + width + 4, 0);
  const title = fitText(table.title, 12, Math.max(24, table.width - 16 - badgeSpace));
  let badgeX = table.x + table.width - 8;
  const badges = table.badges.map((badge, index) => {
    const width = badgeWidths[index];
    badgeX -= width;
    const x = badgeX;
    badgeX -= 4;
    const y = table.y + 10;
    const background = badge.background
      ? `<rect x="${num(x)}" y="${num(y)}" width="${num(width)}" height="14" rx="2" fill="${escapeXml(badge.background)}" fill-opacity="0.15"/>`
      : '';
    return `${background}${text(x + 4, y + 11, badge.text, 10, badge.color)}`;
  }).join('');
  const rows = table.columns.map((column, index) => {
    const top = table.y + HEADER_HEIGHT + index * ROW_HEIGHT;
    const baseline = top + 15;
    const nameX = table.x + 36;
    const inner = Math.max(0, table.width - 44);
    const nameWidth = inner * 0.46;
    const typeWidth = inner * 0.28;
    const commentWidth = Math.max(0, inner - nameWidth - typeWidth);
    const highlight = column.highlighted
      ? `<rect x="${num(table.x + 1)}" y="${num(top)}" width="${num(table.width - 2)}" height="${ROW_HEIGHT}" fill="${theme.highlight}" fill-opacity="0.15"/>`
      : '';
    const key = column.primaryKey
      ? `<circle cx="${num(table.x + 13)}" cy="${num(top + 11)}" r="2.1" fill="none" stroke="${theme.key}" stroke-width="1.2"/><path d="M ${num(table.x + 15)} ${num(top + 11)} H ${num(table.x + 21)} M ${num(table.x + 18.4)} ${num(top + 11)} V ${num(top + 14)} M ${num(table.x + 21)} ${num(top + 11)} V ${num(top + 13.2)}" fill="none" stroke="${theme.key}" stroke-width="1.2" stroke-linecap="round"/>`
      : '';
    const dot = column.linked
      ? `<circle cx="${num(table.x + 27)}" cy="${num(top + 11)}" r="2.2" fill="${theme.linked}"/>`
      : '';
    return [
      highlight,
      key,
      dot,
      text(nameX, baseline, fitText(column.name, 11, nameWidth), 11, theme.text),
      text(nameX + nameWidth, baseline, fitText(column.typeName, 11, typeWidth), 11, theme.type),
      text(nameX + nameWidth + typeWidth, baseline, fitText(column.comment, 11, commentWidth), 11, theme.muted),
    ].join('');
  }).join('');
  const expand = table.expandLabel
    ? `<line x1="${num(table.x)}" y1="${num(table.y + table.height - EXPAND_BAR_HEIGHT)}" x2="${num(table.x + table.width)}" y2="${num(table.y + table.height - EXPAND_BAR_HEIGHT)}" stroke="${escapeXml(theme.border)}" stroke-width="1"/>${text(table.x + 8, table.y + table.height - 7, fitText(table.expandLabel, 10, table.width - 16), 10, theme.muted)}`
    : '';
  const stroke = table.external ? theme.externalBorder : theme.border;
  const dash = table.external ? ' stroke-dasharray="4 3"' : '';
  return [
    `<g data-er-table="1">`,
    `<rect x="${num(table.x)}" y="${num(table.y)}" width="${num(table.width)}" height="${num(table.height)}" rx="6" fill="${escapeXml(theme.card)}" stroke="${escapeXml(stroke)}" stroke-width="1"${dash}/>`,
    `<line x1="${num(table.x)}" y1="${num(table.y + HEADER_HEIGHT)}" x2="${num(table.x + table.width)}" y2="${num(table.y + HEADER_HEIGHT)}" stroke="${escapeXml(theme.border)}" stroke-width="1"/>`,
    text(table.x + 8, table.y + 22, title, 12, theme.title, 600),
    badges,
    rows,
    expand,
    `</g>`,
  ].join('');
}

export function buildErDiagramSvg(diagram: VectorDiagram) {
  if (diagram.tables.length === 0) throw new Error('ER diagram capture is blank');
  let bounds: DiagramBounds = {
    x: diagram.tables[0].x,
    y: diagram.tables[0].y,
    width: diagram.tables[0].width,
    height: diagram.tables[0].height,
  };
  diagram.tables.slice(1).forEach((table) => {
    bounds = includeBounds(bounds, table);
  });
  diagram.edges.forEach((edge) => {
    edge.points.forEach((point) => {
      bounds = includeBounds(bounds, { x: point.x, y: point.y, width: 0, height: 0 });
    });
  });
  const size = exportImageSize(bounds);
  const viewX = bounds.x - ER_EXPORT_PADDING;
  const viewY = bounds.y - ER_EXPORT_PADDING;
  const viewWidth = bounds.width + ER_EXPORT_PADDING * 2;
  const viewHeight = bounds.height + ER_EXPORT_PADDING * 2;
  const background = `<rect x="${num(viewX)}" y="${num(viewY)}" width="${num(viewWidth)}" height="${num(viewHeight)}" fill="${escapeXml(diagram.theme.background)}"/>`;
  const body = [
    background,
    ...diagram.edges.map(edgeMarkup),
    ...diagram.tables.map((table) => tableMarkup(table, diagram.theme)),
  ].join('');
  return `<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="http://www.w3.org/2000/svg" width="${size.width}" height="${size.height}" viewBox="${num(viewX)} ${num(viewY)} ${num(viewWidth)} ${num(viewHeight)}">${body}</svg>`;
}
