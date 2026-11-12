import ELK from 'elkjs/lib/elk.bundled.js';
import type { ElkNode } from 'elkjs/lib/elk-api';
import type { ErRelation, ErTable } from '../../services/table.service';
import { columnKey, relationFromId, relationToId, tableId } from './erDiagramRelations';

export const CARD_WIDTH = 260;
export const HEADER_HEIGHT = 34;
export const ROW_HEIGHT = 22;
export const MAX_VISIBLE_ROWS = 12;
export const EXPAND_BAR_HEIGHT = 22;
const CARD_BORDER = 2;

const elk = new ELK();

export function cardHeight(columnCount: number, expanded = false) {
  const overflow = columnCount > MAX_VISIBLE_ROWS;
  const rows = expanded || !overflow ? Math.max(columnCount, 1) : MAX_VISIBLE_ROWS;
  return HEADER_HEIGHT + rows * ROW_HEIGHT + (overflow ? EXPAND_BAR_HEIGHT : 0) + CARD_BORDER;
}

export interface DiagramCard {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * Keeps the resized card where it is. Cards in the same column at or below its
 * previous bottom move by the height delta, so growing does not cover them and
 * shrinking puts them back. The viewport is left alone.
 */
export function shiftCardsForResize(cards: DiagramCard[], resizedId: string, previousHeight: number): DiagramCard[] {
  const resized = cards.find((card) => card.id === resizedId);
  if (!resized) return cards.map((card) => ({ ...card }));
  const delta = resized.height - previousHeight;
  if (delta === 0) return cards.map((card) => ({ ...card }));
  const previousBottom = resized.y + previousHeight;
  return cards.map((card) => {
    if (card.id === resizedId) return { ...card };
    const center = card.x + card.width / 2;
    const sameColumn = center >= resized.x && center < resized.x + resized.width;
    if (!sameColumn || card.y + 1 < previousBottom) return { ...card };
    return { ...card, y: card.y + delta };
  });
}

/** Keeps linked rows on the card. Extra fields stay behind the expand bar. */
export function visibleColumns<T extends { name: string }>(
  columns: T[],
  linked: ReadonlySet<string>,
  highlighted: ReadonlySet<string>,
  tableKey: string,
  expanded: boolean,
): T[] {
  if (expanded || columns.length <= MAX_VISIBLE_ROWS) return columns;
  const chosen = new Set<string>();
  const take = (want: (column: T) => boolean) => {
    columns.forEach((column) => {
      if (chosen.size >= MAX_VISIBLE_ROWS || chosen.has(column.name)) return;
      if (want(column)) chosen.add(column.name);
    });
  };
  take((column) => highlighted.has(columnKey(tableKey, column.name)));
  take((column) => linked.has(columnKey(tableKey, column.name)));
  take(() => true);
  return columns.filter((column) => chosen.has(column.name));
}

/** Keeps table identity without XML control characters, so image export can serialize the diagram. */
export function toNodeId(tableKey: string) {
  return encodeURIComponent(tableKey);
}

function storedNodeId(id: string) {
  if (!id.includes('\u001f')) return id;
  return toNodeId(id.split('\u001f').join('\0'));
}

export function layoutStorageKey(connectionId: string | number, catalog?: string | null, schema?: string | null) {
  return `er-diagram-layout:${connectionId}:${catalog ?? ''}:${schema ?? ''}`;
}

export interface LayoutPoint {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export type StoredLayout = Record<string, { x: number; y: number }>;

export interface LayoutStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function defaultStore(): LayoutStore {
  return localStorage;
}

export function readStoredLayout(key: string, store: LayoutStore = defaultStore()): StoredLayout | null {
  const raw = store.getItem(key);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const layout: StoredLayout = {};
    Object.entries(parsed as Record<string, unknown>).forEach(([id, value]) => {
      if (!value || typeof value !== 'object') return;
      const point = value as { x?: unknown; y?: unknown };
      if (typeof point.x !== 'number' || typeof point.y !== 'number') return;
      if (!Number.isFinite(point.x) || !Number.isFinite(point.y)) return;
      layout[storedNodeId(id)] = { x: point.x, y: point.y };
    });
    return layout;
  } catch {
    return null;
  }
}

export function writeStoredLayout(key: string, updates: StoredLayout, store: LayoutStore = defaultStore()) {
  const current = readStoredLayout(key, store) ?? {};
  store.setItem(key, JSON.stringify({ ...current, ...updates }));
}

export function clearStoredLayout(key: string, store: LayoutStore = defaultStore()) {
  store.removeItem(key);
}

export function hasStoredLayout(layout: StoredLayout | null) {
  return !!layout && Object.keys(layout).length > 0;
}

const LAYOUT_ORIGIN = 24;
const VIEW_PADDING = 32;

export const ER_MIN_ZOOM = 0.08;
export const ER_MAX_ZOOM = 1.8;
export const ER_MINIMAP_WIDTH = 220;
export const ER_MINIMAP_HEIGHT = 132;
export const ER_ZOOM_BAR_HEIGHT = 32;
export const ER_OVERVIEW_GAP = 8;
/** Matches the margin on `.react-flow__panel`. */
export const ER_PANEL_MARGIN = 15;
/** Content may poke out this far before the minimap appears. */
export const ER_MINIMAP_SHOW_SLACK = 12;
/** Once shown, the minimap stays until every table is back inside this margin. */
export const ER_MINIMAP_HIDE_SLACK = 4;

export function formatZoomPercent(zoom: number) {
  return `${Math.round(zoom * 100)}%`;
}

export function viewportShowsAllTables(
  nodes: Array<{ x: number; y: number; width: number; height: number }>,
  viewport: { x: number; y: number; zoom: number },
  paneWidth: number,
  paneHeight: number,
  slack = ER_MINIMAP_SHOW_SLACK,
) {
  if (nodes.length === 0 || paneWidth <= 0 || paneHeight <= 0 || viewport.zoom <= 0) return true;
  return nodes.every((node) => {
    const left = viewport.x + node.x * viewport.zoom;
    const top = viewport.y + node.y * viewport.zoom;
    const right = left + node.width * viewport.zoom;
    const bottom = top + node.height * viewport.zoom;
    return left >= -slack
      && top >= -slack
      && right <= paneWidth + slack
      && bottom <= paneHeight + slack;
  });
}

export function minimapNeeded(
  currentlyVisible: boolean,
  nodes: Array<{ x: number; y: number; width: number; height: number }>,
  viewport: { x: number; y: number; zoom: number },
  paneWidth: number,
  paneHeight: number,
) {
  const slack = currentlyVisible ? ER_MINIMAP_HIDE_SLACK : ER_MINIMAP_SHOW_SLACK;
  return !viewportShowsAllTables(nodes, viewport, paneWidth, paneHeight, slack);
}

/** Moves the whole diagram so its top-left sits on a fixed origin. */
export function normalizeLayout(points: LayoutPoint[], origin = LAYOUT_ORIGIN): LayoutPoint[] {
  if (points.length === 0) return points;
  const minX = Math.min(...points.map((point) => point.x));
  const minY = Math.min(...points.map((point) => point.y));
  const dx = origin - minX;
  const dy = origin - minY;
  if (dx === 0 && dy === 0) return points;
  return points.map((point) => ({ ...point, x: point.x + dx, y: point.y + dy }));
}

export function layoutBounds(points: Array<{ x: number; y: number; width: number; height: number }>) {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  points.forEach((point) => {
    minX = Math.min(minX, point.x);
    minY = Math.min(minY, point.y);
    maxX = Math.max(maxX, point.x + point.width);
    maxY = Math.max(maxY, point.y + point.height);
  });
  return { x: minX, y: minY, width: Math.max(0, maxX - minX), height: Math.max(0, maxY - minY) };
}

/**
 * Fits every card into the window. Extra space stays below the diagram,
 * so a short graph starts at the top instead of floating in the lower half.
 */
export function viewportForDiagram(
  bounds: { x: number; y: number; width: number; height: number },
  viewWidth: number,
  viewHeight: number,
): { x: number; y: number; zoom: number } | null {
  if (viewWidth <= VIEW_PADDING * 2 || viewHeight <= VIEW_PADDING * 2) return null;
  if (bounds.width <= 0 || bounds.height <= 0) return null;
  const fit = Math.min(
    (viewWidth - VIEW_PADDING * 2) / bounds.width,
    (viewHeight - VIEW_PADDING * 2) / bounds.height,
  );
  const zoom = Math.min(1, Math.max(ER_MIN_ZOOM, fit));
  return {
    x: (viewWidth - bounds.width * zoom) / 2 - bounds.x * zoom,
    y: VIEW_PADDING - bounds.y * zoom,
    zoom,
  };
}

export function mergeStoredPositions(points: LayoutPoint[], stored: StoredLayout | null): LayoutPoint[] {
  if (!stored) return points;
  return points.map((point) => {
    const saved = stored[point.id];
    if (!saved) return point;
    return { ...point, x: saved.x, y: saved.y };
  });
}

function gridLayout(tables: ErTable[], expandedIds: ReadonlySet<string>): LayoutPoint[] {
  const columns = Math.max(1, Math.ceil(Math.sqrt(Math.max(tables.length, 1))));
  const heights = tables.map((table) => cardHeight(table.columns.length, expandedIds.has(toNodeId(tableId(table)))));
  const rowCount = Math.ceil(tables.length / columns);
  const rowHeights: number[] = [];
  for (let row = 0; row < rowCount; row += 1) {
    let height = 0;
    for (let column = 0; column < columns; column += 1) {
      height = Math.max(height, heights[row * columns + column] ?? 0);
    }
    rowHeights.push(height);
  }
  return tables.map((table, index) => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const y = 24 + rowHeights.slice(0, row).reduce((sum, height) => sum + height + 56, 0);
    return {
      id: toNodeId(tableId(table)),
      x: 24 + column * (CARD_WIDTH + 72),
      y,
      width: CARD_WIDTH,
      height: heights[index],
    };
  });
}

export async function layoutDiagram(
  tables: ErTable[],
  relations: ErRelation[],
  expandedIds: ReadonlySet<string> = new Set(),
): Promise<LayoutPoint[]> {
  if (tables.length === 0) return [];
  const points = tables.map((table) => ({
    id: toNodeId(tableId(table)),
    width: CARD_WIDTH,
    height: cardHeight(table.columns.length, expandedIds.has(toNodeId(tableId(table)))),
  }));
  const known = new Set(points.map((point) => point.id));
  const edges = relations.flatMap((relation, index) => {
    const source = toNodeId(relationFromId(relation));
    const target = toNodeId(relationToId(relation));
    if (!known.has(source) || !known.has(target) || source === target) return [];
    return [{ id: `e${index}`, sources: [source], targets: [target] }];
  });
  try {
    const graph = await elk.layout({
      id: 'root',
      layoutOptions: {
        'elk.algorithm': 'layered',
        'elk.direction': 'RIGHT',
        'elk.edgeRouting': 'ORTHOGONAL',
        'elk.spacing.nodeNode': '48',
        'elk.spacing.componentComponent': '48',
        'elk.layered.spacing.nodeNodeBetweenLayers': '100',
        'elk.padding': '[top=24,left=24,bottom=24,right=24]',
      },
      children: points.map((point) => ({
        id: point.id,
        width: point.width,
        height: point.height,
      })),
      edges,
    } satisfies ElkNode);
    const byId = new Map((graph.children ?? []).map((child) => [child.id, child]));
    return normalizeLayout(points.map((point) => {
      const placed = byId.get(point.id);
      return {
        ...point,
        x: placed?.x ?? LAYOUT_ORIGIN,
        y: placed?.y ?? LAYOUT_ORIGIN,
      };
    }));
  } catch {
    return normalizeLayout(gridLayout(tables, expandedIds));
  }
}
