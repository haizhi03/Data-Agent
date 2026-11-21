import { useContext } from 'react';
import { BaseEdge, Position, type EdgeProps } from '@xyflow/react';
import { ER_ROSE, ER_VIOLET, type ErRelationEdge } from './erDiagramEdges';
import { ErDiagramRectsContext } from './erDiagramInteraction';
import { routeAroundNodes, type RouteSide } from './erDiagramRoute';
import type { EndMark } from './erDiagramRelations';

function sideOf(position: Position): RouteSide {
  return position === Position.Left ? 'left' : 'right';
}

function Mark({ id, mark, color }: { id: string; mark: EndMark; color: string }) {
  return (
    <marker
      id={id}
      viewBox="0 0 14 14"
      refX="13"
      refY="7"
      markerWidth="13"
      markerHeight="13"
      orient="auto-start-reverse"
      markerUnits="userSpaceOnUse"
    >
      {mark === 'one' ? (
        <path d="M11 1 V13" fill="none" stroke={color} strokeWidth="1.6" />
      ) : (
        <path d="M1 7 L13 1 M1 7 L13 7 M1 7 L13 13" fill="none" stroke={color} strokeWidth="1.6" strokeLinecap="round" />
      )}
    </marker>
  );
}

export function ErRelationEdgeView({
  id,
  source,
  target,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
}: EdgeProps<ErRelationEdge>) {
  const rects = useContext(ErDiagramRectsContext);
  const points = routeAroundNodes(
    { x: sourceX, y: sourceY },
    { x: targetX, y: targetY },
    sideOf(sourcePosition),
    sideOf(targetPosition),
    rects,
    source,
    target,
    data?.lane ?? 0,
  );
  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ');
  const color = data?.manyToMany ? ER_VIOLET : ER_ROSE;
  const safeId = id.replace(/[^a-zA-Z0-9_-]/g, '_');
  const startId = `er-${safeId}-start`;
  const endId = `er-${safeId}-end`;
  return (
    <g>
      <defs>
        <Mark id={startId} mark={data?.fromMark ?? 'many'} color={color} />
        <Mark id={endId} mark={data?.toMark ?? 'one'} color={color} />
      </defs>
      <BaseEdge
        id={id}
        path={path}
        interactionWidth={data?.focusKey ? 18 : 0}
        markerStart={`url(#${startId})`}
        markerEnd={`url(#${endId})`}
        style={{
          stroke: color,
          strokeWidth: data?.focusKey ? 2.4 : 1.6,
          strokeDasharray: data?.cross ? '5 4' : undefined,
          opacity: data?.dimmed ? 0.28 : 1,
          fill: 'none',
          pointerEvents: data?.focusKey ? 'stroke' : 'none',
          cursor: data?.focusKey ? 'pointer' : undefined,
        }}
      />
      {data?.focusKey ? <title>点击隐藏这条线</title> : null}
    </g>
  );
}
