import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type WheelEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { KeyRound } from 'lucide-react';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { tableService, type ErDiagram, type ErRelation } from '../../services/table.service';
import type { ErDiagramTabMetadata } from '../../types/tab';

const CARD_WIDTH = 260;
const COLUMN_GAP = 72;
const ROW_GAP = 56;
const HEADER_HEIGHT = 34;
const ROW_HEIGHT = 22;
const MAX_VISIBLE_ROWS = 12;

interface CardBox {
  name: string;
  x: number;
  y: number;
  width: number;
  height: number;
  col: number;
  row: number;
}

interface Point {
  x: number;
  y: number;
}

interface Channels {
  rowCount: number;
  vertical: number[];
  horizontal: number[];
}

interface ColumnFocus {
  table: string;
  column: string;
}

interface RelationLine {
  key: string;
  d: string;
  label: string;
}

function columnKey(table: string, column: string) {
  return `${table}\0${column}`;
}

function cardHeight(columnCount: number) {
  const rows = Math.min(Math.max(columnCount, 1), MAX_VISIBLE_ROWS);
  return HEADER_HEIGHT + rows * ROW_HEIGHT + 8;
}

function layoutCards(diagram: ErDiagram): { boxes: CardBox[]; width: number; height: number } {
  const tables = diagram.tables;
  const columns = Math.max(1, Math.ceil(Math.sqrt(tables.length)));
  const rowCount = Math.ceil(tables.length / columns);
  const rowHeights: number[] = [];
  for (let row = 0; row < rowCount; row += 1) {
    let height = 80;
    for (let column = 0; column < columns; column += 1) {
      const table = tables[row * columns + column];
      if (table) height = Math.max(height, cardHeight(table.columns.length));
    }
    rowHeights.push(height);
  }

  const boxes: CardBox[] = [];
  tables.forEach((table, index) => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const rowY = rowHeights.slice(0, row).reduce((sum, height) => sum + height + ROW_GAP, 24);
    boxes.push({
      name: table.name,
      x: 24 + column * (CARD_WIDTH + COLUMN_GAP),
      y: rowY,
      width: CARD_WIDTH,
      height: cardHeight(table.columns.length),
      col: column,
      row,
    });
  });

  const width = 48 + columns * CARD_WIDTH + Math.max(0, columns - 1) * COLUMN_GAP;
  const height = 48 + rowHeights.reduce((sum, height) => sum + height, 0) + Math.max(0, rowCount - 1) * ROW_GAP;
  return { boxes, width, height };
}

function relationsForColumn(relations: ErRelation[], focus: ColumnFocus) {
  return relations.filter((relation) => (
    (relation.fromTable === focus.table && relation.fromColumn === focus.column)
    || (relation.toTable === focus.table && relation.toColumn === focus.column)
  ));
}

function relationLabel(relation: ErRelation) {
  return `${relation.fromTable}.${relation.fromColumn} → ${relation.toTable}.${relation.toColumn}`;
}

function revealRow(row: HTMLElement) {
  const scroller = row.parentElement;
  if (!scroller) return;
  const scrollerRect = scroller.getBoundingClientRect();
  const rowRect = row.getBoundingClientRect();
  if (rowRect.top < scrollerRect.top) {
    scroller.scrollTop -= scrollerRect.top - rowRect.top;
  } else if (rowRect.bottom > scrollerRect.bottom) {
    scroller.scrollTop += rowRect.bottom - scrollerRect.bottom;
  }
}

function endpoint(canvas: HTMLElement, row: HTMLElement, edge: 'left' | 'right', scale: number) {
  const canvasRect = canvas.getBoundingClientRect();
  const rowRect = row.getBoundingClientRect();
  const x = (edge === 'right' ? rowRect.right : rowRect.left) - canvasRect.left;
  const y = rowRect.top + rowRect.height / 2 - canvasRect.top;
  return {
    x: Math.round((x / scale + (edge === 'right' ? 2 : -2)) * 10) / 10,
    y: Math.round((y / scale) * 10) / 10,
  };
}

function buildChannels(boxes: CardBox[]): Channels {
  if (boxes.length === 0) return { rowCount: 0, vertical: [12], horizontal: [12] };
  const columnCount = Math.max(...boxes.map((box) => box.col)) + 1;
  const rowCount = Math.max(...boxes.map((box) => box.row)) + 1;
  const vertical = [12];
  for (let col = 0; col < columnCount - 1; col += 1) {
    const left = boxes.find((box) => box.col === col);
    const right = boxes.find((box) => box.col === col + 1);
    if (left && right) vertical.push((left.x + left.width + right.x) / 2);
  }
  const lastColumn = boxes.find((box) => box.col === columnCount - 1);
  vertical.push(lastColumn ? lastColumn.x + lastColumn.width + 12 : 12);

  const horizontal = [12];
  for (let row = 0; row < rowCount - 1; row += 1) {
    const bottom = Math.max(...boxes.filter((box) => box.row === row).map((box) => box.y + box.height));
    const top = Math.min(...boxes.filter((box) => box.row === row + 1).map((box) => box.y));
    horizontal.push((bottom + top) / 2);
  }
  const lastRowBottom = Math.max(...boxes.filter((box) => box.row === rowCount - 1).map((box) => box.y + box.height));
  horizontal.push(lastRowBottom + 12);
  return { rowCount, vertical, horizontal };
}

function appendPoint(points: Point[], x: number, y: number) {
  const rounded = { x: Math.round(x * 10) / 10, y: Math.round(y * 10) / 10 };
  const last = points[points.length - 1];
  if (!last) {
    points.push(rounded);
    return;
  }
  if (Math.abs(last.x - rounded.x) < 0.5 && Math.abs(last.y - rounded.y) < 0.5) return;
  if (Math.abs(last.x - rounded.x) >= 0.5 && Math.abs(last.y - rounded.y) >= 0.5) {
    points.push({ x: last.x, y: rounded.y });
  }
  points.push(rounded);
}

function routeEdges(fromBox: CardBox, toBox: CardBox): { fromEdge: 'left' | 'right'; toEdge: 'left' | 'right' } {
  if (fromBox.name === toBox.name || fromBox.col === toBox.col) {
    return { fromEdge: 'right', toEdge: 'right' };
  }
  if (Math.abs(fromBox.col - toBox.col) === 1) {
    const fromIsLeft = fromBox.col < toBox.col;
    return {
      fromEdge: fromIsLeft ? 'right' : 'left',
      toEdge: fromIsLeft ? 'left' : 'right',
    };
  }
  const goingRight = toBox.col > fromBox.col;
  return {
    fromEdge: goingRight ? 'right' : 'left',
    toEdge: goingRight ? 'left' : 'right',
  };
}

function routeAroundCards(fromBox: CardBox, toBox: CardBox, from: Point, to: Point, channels: Channels, laneOffset: number): Point[] {
  const points: Point[] = [];
  appendPoint(points, from.x, from.y);

  if (fromBox.name === toBox.name || fromBox.col === toBox.col) {
    const gutter = channels.vertical[fromBox.col + 1] + laneOffset;
    appendPoint(points, gutter, from.y);
    appendPoint(points, gutter, to.y);
    appendPoint(points, to.x, to.y);
    return points;
  }

  if (Math.abs(fromBox.col - toBox.col) === 1) {
    const gutter = channels.vertical[Math.min(fromBox.col, toBox.col) + 1] + laneOffset;
    appendPoint(points, gutter, from.y);
    appendPoint(points, gutter, to.y);
    appendPoint(points, to.x, to.y);
    return points;
  }

  const goingRight = toBox.col > fromBox.col;
  const sourceGutter = channels.vertical[goingRight ? fromBox.col + 1 : fromBox.col] + laneOffset;
  const targetGutter = channels.vertical[goingRight ? toBox.col : toBox.col + 1] + laneOffset;
  const gapIndex = fromBox.row === toBox.row
    ? (fromBox.row < channels.rowCount - 1 ? fromBox.row + 1 : fromBox.row)
    : (toBox.row > fromBox.row ? fromBox.row + 1 : fromBox.row);
  const gap = channels.horizontal[gapIndex] + laneOffset;
  appendPoint(points, sourceGutter, from.y);
  appendPoint(points, sourceGutter, gap);
  appendPoint(points, targetGutter, gap);
  appendPoint(points, targetGutter, to.y);
  appendPoint(points, to.x, to.y);
  return points;
}

function pointsToPath(points: Point[]) {
  if (points.length === 0) return '';
  const simplified = [points[0]];
  for (let index = 1; index < points.length - 1; index += 1) {
    const previous = simplified[simplified.length - 1];
    const current = points[index];
    const next = points[index + 1];
    const vertical = previous.x === current.x && current.x === next.x;
    const horizontal = previous.y === current.y && current.y === next.y;
    if (!vertical && !horizontal) simplified.push(current);
  }
  simplified.push(points[points.length - 1]);
  return simplified.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ');
}

interface ErDiagramTabProps {
  metadata: ErDiagramTabMetadata;
}

export function ErDiagramTab({ metadata }: ErDiagramTabProps) {
  const { t } = useTranslation();
  const [diagram, setDiagram] = useState<ErDiagram | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState({ x: 0, y: 0, scale: 1 });
  const [hover, setHover] = useState<ColumnFocus | null>(null);
  const [pinned, setPinned] = useState<ColumnFocus | null>(null);
  const [lines, setLines] = useState<RelationLine[]>([]);
  const drag = useRef<{ pointerX: number; pointerY: number; x: number; y: number } | null>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef(new Map<string, HTMLDivElement>());

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    setHover(null);
    setPinned(null);
    tableService.getErDiagram(
      String(metadata.connectionId),
      metadata.catalog ?? undefined,
      metadata.schema ?? undefined,
    ).then((result) => {
      if (!cancelled) setDiagram(result);
    }).catch((err: unknown) => {
      if (!cancelled) setError(err instanceof Error ? err.message : t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FAILED));
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [metadata.catalog, metadata.connectionId, metadata.schema, t]);

  const layout = useMemo(() => (diagram ? layoutCards(diagram) : null), [diagram]);
  const boxByName = useMemo(() => {
    const map = new Map<string, CardBox>();
    layout?.boxes.forEach((box) => map.set(box.name, box));
    return map;
  }, [layout]);
  const linkedColumns = useMemo(() => {
    const linked = new Set<string>();
    diagram?.relations.forEach((relation) => {
      linked.add(columnKey(relation.fromTable, relation.fromColumn));
      linked.add(columnKey(relation.toTable, relation.toColumn));
    });
    return linked;
  }, [diagram]);

  const active = hover ?? pinned;
  const activeRelations = useMemo(
    () => (diagram && active ? relationsForColumn(diagram.relations, active) : []),
    [active, diagram],
  );
  const highlightedColumns = useMemo(() => {
    const highlighted = new Set<string>();
    activeRelations.forEach((relation) => {
      highlighted.add(columnKey(relation.fromTable, relation.fromColumn));
      highlighted.add(columnKey(relation.toTable, relation.toColumn));
    });
    return highlighted;
  }, [activeRelations]);

  const measureLines = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || !active || activeRelations.length === 0) {
      setLines((current) => (current.length === 0 ? current : []));
      return;
    }
    activeRelations.forEach((relation) => {
      const fromRow = rowRefs.current.get(columnKey(relation.fromTable, relation.fromColumn));
      const toRow = rowRefs.current.get(columnKey(relation.toTable, relation.toColumn));
      if (fromRow) revealRow(fromRow);
      if (toRow) revealRow(toRow);
    });
    const channels = buildChannels([...boxByName.values()]);
    const laneSpread = 8;
    const next: RelationLine[] = [];
    activeRelations.forEach((relation, index) => {
      const fromRow = rowRefs.current.get(columnKey(relation.fromTable, relation.fromColumn));
      const toRow = rowRefs.current.get(columnKey(relation.toTable, relation.toColumn));
      const fromBox = boxByName.get(relation.fromTable);
      const toBox = boxByName.get(relation.toTable);
      if (!fromRow || !toRow || !fromBox || !toBox) return;
      const edges = routeEdges(fromBox, toBox);
      const from = endpoint(canvas, fromRow, edges.fromEdge, view.scale);
      const to = endpoint(canvas, toRow, edges.toEdge, view.scale);
      const lane = index - (activeRelations.length - 1) / 2;
      const laneOffset = Math.max(-10, Math.min(10, lane * laneSpread));
      next.push({
        key: `${relationLabel(relation)}-${index}`,
        d: pointsToPath(routeAroundCards(fromBox, toBox, from, to, channels, laneOffset)),
        label: relationLabel(relation),
      });
    });
    setLines((current) => {
      if (current.length === next.length && current.every((line, index) => (
        line.key === next[index].key && line.d === next[index].d
      ))) {
        return current;
      }
      return next;
    });
  }, [active, activeRelations, boxByName, view.scale]);

  useLayoutEffect(() => {
    measureLines();
  }, [measureLines, layout]);

  const onWheel = (event: WheelEvent<HTMLDivElement>) => {
    event.preventDefault();
    const next = Math.min(1.8, Math.max(0.45, view.scale + (event.deltaY < 0 ? 0.08 : -0.08)));
    setView((current) => ({ ...current, scale: next }));
  };

  const togglePin = (focus: ColumnFocus) => {
    setPinned((current) => (
      current?.table === focus.table && current.column === focus.column ? null : focus
    ));
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex min-h-10 shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b theme-border px-3 py-2 text-xs theme-text-secondary">
        <span className="theme-text-primary font-medium">{t(I18N_KEYS.EXPLORER.ER_DIAGRAM)}</span>
        <span>{metadata.databaseName || metadata.schemaName || metadata.connectionName}</span>
        {diagram && (
          <span>
            {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SUMMARY, {
              tables: diagram.tables.length,
              relations: diagram.relations.length,
            })}
          </span>
        )}
        {diagram?.truncated && (
          <span>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_TRUNCATED, { count: diagram.tables.length })}</span>
        )}
        {activeRelations.length > 0 ? (
          activeRelations.map((relation, index) => (
            <span key={`${relationLabel(relation)}-${index}`} className="theme-text-primary">
              {relationLabel(relation)}
            </span>
          ))
        ) : (
          <span className="truncate">{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_HINT)}</span>
        )}
      </div>
      <div
        className="relative min-h-0 flex-1 overflow-hidden"
        style={{
          backgroundImage: 'radial-gradient(circle, var(--border-color) 1px, transparent 1px)',
          backgroundSize: '18px 18px',
          cursor: drag.current ? 'grabbing' : 'grab',
        }}
        onWheel={onWheel}
        onPointerDown={(event) => {
          if (event.button !== 0) return;
          drag.current = { pointerX: event.clientX, pointerY: event.clientY, x: view.x, y: view.y };
          event.currentTarget.setPointerCapture(event.pointerId);
        }}
        onPointerMove={(event) => {
          if (!drag.current) return;
          setView((current) => ({
            ...current,
            x: drag.current!.x + event.clientX - drag.current!.pointerX,
            y: drag.current!.y + event.clientY - drag.current!.pointerY,
          }));
        }}
        onPointerUp={() => {
          drag.current = null;
        }}
      >
        {loading && (
          <div className="absolute inset-0 flex items-center justify-center text-sm theme-text-secondary">
            {t(I18N_KEYS.EXPLORER.LOADING)}
          </div>
        )}
        {!loading && error && (
          <div className="absolute inset-0 flex items-center justify-center px-6 text-sm text-destructive">
            {error}
          </div>
        )}
        {!loading && diagram && diagram.tables.length === 0 && (
          <div className="absolute inset-0 flex items-center justify-center text-sm theme-text-secondary">
            {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EMPTY)}
          </div>
        )}
        {layout && diagram && diagram.tables.length > 0 && (
          <div
            ref={canvasRef}
            style={{
              transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})`,
              transformOrigin: '0 0',
              width: layout.width,
              height: layout.height,
              position: 'relative',
            }}
          >
            <svg className="pointer-events-none absolute inset-0 h-full w-full" width={layout.width} height={layout.height}>
              {lines.map((line) => (
                <path
                  key={line.key}
                  d={line.d}
                  fill="none"
                  stroke="#e11d48"
                  strokeWidth="1.6"
                  strokeLinejoin="round"
                  strokeLinecap="round"
                />
              ))}
            </svg>
            {diagram.tables.map((table) => {
              const box = boxByName.get(table.name);
              if (!box) return null;
              return (
                <div
                  key={table.name}
                  className="absolute overflow-hidden rounded-md border theme-border theme-bg-popup shadow-sm"
                  style={{ left: box.x, top: box.y, width: box.width, height: box.height }}
                >
                  <div className="flex h-[34px] items-center border-b theme-border px-2 text-xs font-semibold theme-text-primary">
                    <span className="truncate" title={table.comment || table.name}>{table.name}</span>
                  </div>
                  <div className="max-h-[264px] overflow-auto" onScroll={measureLines}>
                    {table.columns.map((column) => {
                      const key = columnKey(table.name, column.name);
                      const linked = linkedColumns.has(key);
                      const highlighted = highlightedColumns.has(key);
                      return (
                        <div
                          key={column.name}
                          ref={(node) => {
                            if (node) rowRefs.current.set(key, node);
                            else rowRefs.current.delete(key);
                          }}
                          className={`flex h-[22px] items-center gap-1 px-2 text-[11px] ${highlighted ? 'bg-rose-500/15' : ''} ${linked ? 'cursor-pointer' : ''}`}
                          onPointerDown={(event) => {
                            if (!linked) return;
                            event.stopPropagation();
                          }}
                          onClick={(event) => {
                            if (!linked) return;
                            event.stopPropagation();
                            togglePin({ table: table.name, column: column.name });
                          }}
                          onPointerEnter={() => {
                            if (linked) setHover({ table: table.name, column: column.name });
                          }}
                          onPointerLeave={() => {
                            setHover((current) => (
                              current?.table === table.name && current.column === column.name ? null : current
                            ));
                          }}
                        >
                          <span className="w-3 shrink-0 text-amber-500">
                            {column.primaryKey ? <KeyRound className="h-3 w-3" /> : null}
                          </span>
                          <span className="flex w-2 shrink-0 justify-center">
                            {linked ? <span className="h-1.5 w-1.5 rounded-full bg-rose-500" /> : null}
                          </span>
                          <span className="w-[38%] truncate theme-text-primary" title={column.name}>{column.name}</span>
                          <span className="w-[28%] truncate text-sky-500" title={column.typeName}>{column.typeName}</span>
                          <span className="min-w-0 flex-1 truncate theme-text-secondary" title={column.comment}>{column.comment}</span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
