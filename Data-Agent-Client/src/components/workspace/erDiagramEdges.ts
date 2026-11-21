import type { Edge } from '@xyflow/react';
import type { ErRelation } from '../../services/table.service';
import { relationFocusKey } from './erDiagramFocus';
import {
  anchorColumns,
  endMarks,
  isCrossRelation,
  relationFromId,
  relationToId,
  relationTouches,
  type ColumnFocus,
  type EndMark,
} from './erDiagramRelations';
import { toNodeId, visibleColumns } from './erDiagramLayout';

export const ER_ROSE = '#e11d48';
export const ER_VIOLET = '#7c3aed';

export type ErEdgeData = {
  cross: boolean;
  manyToMany: boolean;
  fromMark: EndMark;
  toMark: EndMark;
  lane: number;
  dimmed: boolean;
  /** Set while this line is pinned by "how these connect". Clicking it removes just this line. */
  focusKey?: string;
};

export type ErRelationEdge = Edge<ErEdgeData, 'er'>;

export interface LayoutNode {
  id: string;
  position: { x: number; y: number };
  width?: number | null;
  expanded?: boolean;
  columns?: { name: string }[];
  tableKey?: string;
}

export function columnHandleId(role: 'source' | 'target', side: 'left' | 'right', column: string) {
  return `${role}-${side}-${encodeURIComponent(column)}`;
}

export function overflowHandleId(role: 'source' | 'target', side: 'left' | 'right') {
  return `${role}-${side}-overflow`;
}

function anchorHandle(
  node: LayoutNode,
  role: 'source' | 'target',
  side: 'left' | 'right',
  column: string,
  linked: ReadonlySet<string>,
  highlighted: ReadonlySet<string>,
) {
  if (!node.columns || !node.tableKey) return columnHandleId(role, side, column);
  const visible = visibleColumns(node.columns, linked, highlighted, node.tableKey, !!node.expanded);
  if (visible.some((item) => item.name === column)) return columnHandleId(role, side, column);
  return overflowHandleId(role, side);
}

function pairKey(relation: ErRelation) {
  const fromId = relationFromId(relation);
  const toId = relationToId(relation);
  return fromId < toId ? `${fromId}\0${toId}` : `${toId}\0${fromId}`;
}

export function pairLaneOffsets(relations: ErRelation[]) {
  const keys = relations.map(pairKey);
  const counts = new Map<string, number>();
  keys.forEach((key) => counts.set(key, (counts.get(key) ?? 0) + 1));
  const seen = new Map<string, number>();
  return keys.map((key) => {
    const index = seen.get(key) ?? 0;
    seen.set(key, index + 1);
    return index - ((counts.get(key) ?? 1) - 1) / 2;
  });
}

export function buildDiagramEdges(
  nodes: LayoutNode[],
  relations: ErRelation[],
  focus: ColumnFocus | null,
  showAllLines: boolean,
  linked: ReadonlySet<string> = new Set(),
  highlighted: ReadonlySet<string> = new Set(),
  pinnedRelations: ErRelation[] | null = null,
): ErRelationEdge[] {
  if (nodes.length === 0 || relations.length === 0) return [];
  const usePinned = pinnedRelations != null;
  const active = usePinned
    ? pinnedRelations
    : (focus ? relations.filter((relation) => relationTouches(relation, focus)) : []);
  const drawn = usePinned ? pinnedRelations : (showAllLines ? relations : active);
  if (!usePinned && !showAllLines && (!focus || active.length === 0)) return [];
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const lanes = pairLaneOffsets(drawn);
  return drawn.flatMap((relation, index) => {
    const sourceId = toNodeId(relationFromId(relation));
    const targetId = toNodeId(relationToId(relation));
    const source = byId.get(sourceId);
    const target = byId.get(targetId);
    const anchors = anchorColumns(relation, focus ?? { tableId: relationFromId(relation), column: relation.fromColumn });
    if (!source || !target || !anchors.from || !anchors.to) return [];
    const sourceCenter = source.position.x + (source.width ?? 260) / 2;
    const targetCenter = target.position.x + (target.width ?? 260) / 2;
    const sourceIsLeft = sourceCenter <= targetCenter;
    const marks = endMarks(relation);
    return [{
      id: `${sourceId}->${targetId}:${anchors.from}->${anchors.to}:${index}`,
      type: 'er' as const,
      source: sourceId,
      target: targetId,
      sourceHandle: anchorHandle(source, 'source', sourceIsLeft ? 'right' : 'left', anchors.from, linked, highlighted),
      targetHandle: anchorHandle(target, 'target', sourceIsLeft ? 'left' : 'right', anchors.to, linked, highlighted),
      selectable: false,
      focusable: false,
      data: {
        cross: isCrossRelation(relation),
        manyToMany: relation.cardinality === 'MANY_TO_MANY',
        fromMark: marks.from,
        toMark: marks.to,
        lane: lanes[index] ?? 0,
        dimmed: !usePinned && showAllLines && focus != null && !active.includes(relation),
        focusKey: usePinned ? relationFocusKey(relation) : undefined,
      },
    }];
  });
}
