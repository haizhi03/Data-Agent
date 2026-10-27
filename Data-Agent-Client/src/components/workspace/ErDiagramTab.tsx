import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import {
  Background,
  BackgroundVariant,
  ReactFlow,
  ReactFlowProvider,
  getNodesBounds,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { tableService, type ErDiagram, type ErTable } from '../../services/table.service';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { TableDblClickConsoleTargetEnum } from '../../constants/workspacePreferences';
import type { ConsoleTabMetadata, ErDiagramTabMetadata } from '../../types/tab';
import { useToast } from '../../hooks/useToast';
import { useTheme } from '../../hooks/useTheme';
import {
  collectVectorEdges,
  downloadBlob,
  erDiagramCsvFilename,
  erDiagramFilename,
  renderErDiagramPng,
  renderErDiagramSvg,
  vectorTheme,
  type ErDiagramExportFormat,
} from './erDiagramExport';
import { erDiagramCsvHeaders, renderErDiagramCsv } from './erDiagramCsv';
import { renderErDiagramJson, toErDiagramDocument } from './erDiagramDocument';
import { renderErDiagramMermaid } from './erDiagramMermaid';
import { renderErDiagramPdf } from './erDiagramPdf';
import { vectorTablesFromNodes } from './erDiagramVectorSvg';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../ui/DropdownMenu';
import { ErRelationEdgeView } from './ErDiagramEdge';
import { ErTableNode, type ErTableNodeType } from './ErDiagramNode';
import { ErDiagramMiniMap } from './ErDiagramMiniMap';
import { ErDiagramZoomBar } from './ErDiagramZoomBar';
import { buildDiagramEdges, ER_ROSE, ER_VIOLET, type ErRelationEdge } from './erDiagramEdges';
import { ErDiagramInteractionContext, ErDiagramRectsContext } from './erDiagramInteraction';
import {
  CARD_WIDTH,
  ER_MAX_ZOOM,
  ER_MIN_ZOOM,
  cardHeight,
  clearStoredLayout,
  hasStoredLayout,
  layoutBounds,
  layoutDiagram,
  layoutStorageKey,
  mergeStoredPositions,
  readStoredLayout,
  toNodeId,
  viewportForDiagram,
  writeStoredLayout,
  type LayoutPoint,
} from './erDiagramLayout';
import {
  cardinalityLabel,
  collapseJunctions,
  columnKey,
  filterTablesByName,
  junctionTableId,
  relationFromId,
  relationMapping,
  relationToId,
  relationTouches,
  sideColumns,
  tableId,
  viaTableName,
  type ColumnFocus,
  type EndMark,
} from './erDiagramRelations';
import type { DiagramRect } from './erDiagramRoute';

const nodeTypes = { erTable: ErTableNode };
const edgeTypes = { er: ErRelationEdgeView };

function LegendSample({ mark, label, color, dashed }: { mark?: EndMark; label: string; color?: string; dashed?: boolean }) {
  const stroke = color ?? 'currentColor';
  return (
    <span className="inline-flex items-center gap-1">
      <svg width="26" height="12" aria-hidden="true">
        {mark === 'many' ? (
          <path d="M1 6 L8 1 M1 6 H25 M1 6 L8 11" fill="none" stroke={stroke} strokeWidth="1.4" strokeDasharray={dashed ? '3 2' : undefined} />
        ) : mark === 'one' ? (
          <path d="M2 1 V11 M6 6 H25" fill="none" stroke={stroke} strokeWidth="1.4" />
        ) : (
          <path d="M1 6 H25" fill="none" stroke={stroke} strokeWidth="1.4" strokeDasharray="3 2" />
        )}
      </svg>
      <span>{label}</span>
    </span>
  );
}

interface ErDiagramTabProps {
  metadata: ErDiagramTabMetadata;
}

function tableLocation(table: ErTable, metadata: ErDiagramTabMetadata) {
  return {
    catalog: table.catalog || metadata.catalog || '',
    schema: table.schema || metadata.schema || '',
  };
}

function toFlowNodes(tables: ErTable[], points: LayoutPoint[], previous: ErTableNodeType[] = []): ErTableNodeType[] {
  const byId = new Map(points.map((point) => [point.id, point]));
  const expandedIds = new Set(previous.filter((node) => node.data.expanded).map((node) => node.id));
  return tables.map((table) => {
    const id = toNodeId(tableId(table));
    const point = byId.get(id);
    const expanded = expandedIds.has(id);
    return {
      id,
      type: 'erTable',
      position: { x: point?.x ?? 24, y: point?.y ?? 24 },
      width: point?.width ?? CARD_WIDTH,
      height: cardHeight(table.columns.length, expanded),
      data: { table, expanded },
      dragHandle: '.er-drag-handle',
      selectable: false,
      deletable: false,
    };
  });
}

export function ErDiagramTab({ metadata }: ErDiagramTabProps) {
  return (
    <ReactFlowProvider key={layoutStorageKey(metadata.connectionId, metadata.catalog, metadata.schema)}>
      <ErDiagramCanvas metadata={metadata} />
    </ReactFlowProvider>
  );
}

function ErDiagramCanvas({ metadata }: ErDiagramTabProps) {
  const { t } = useTranslation();
  const { theme } = useTheme();
  const toast = useToast();
  const openTab = useWorkspaceStore((state) => state.openTab);
  const tabs = useWorkspaceStore((state) => state.tabs);
  const updateTabContent = useWorkspaceStore((state) => state.updateTabContent);
  const updateTabMetadata = useWorkspaceStore((state) => state.updateTabMetadata);
  const switchTab = useWorkspaceStore((state) => state.switchTab);
  const tableDblClickConsoleTarget = useWorkspaceStore((state) => state.tableDblClickConsoleTarget);
  const { getNodes, setViewport } = useReactFlow();
  const [diagram, setDiagram] = useState<ErDiagram | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [hover, setHoverState] = useState<ColumnFocus | null>(null);
  const [pinned, setPinned] = useState<ColumnFocus | null>(null);
  const [collapseJunctionTables, setCollapseJunctionTables] = useState(false);
  const [showAllLines, setShowAllLines] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [layouting, setLayouting] = useState(false);
  const [hasCustomLayout, setHasCustomLayout] = useState(false);
  const [tableNameFilter, setTableNameFilter] = useState('');
  const [exactTableFilter, setExactTableFilter] = useState(false);
  const [nodes, setNodes, onNodesChange] = useNodesState<ErTableNodeType>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<ErRelationEdge>([]);
  const draggingRef = useRef(false);
  const flowRef = useRef<HTMLDivElement>(null);
  const requestId = useRef(0);
  const framedToken = useRef(-1);
  const [layoutVersion, setLayoutVersion] = useState(0);
  const [frameReady, setFrameReady] = useState(false);
  const storageKey = layoutStorageKey(metadata.connectionId, metadata.catalog, metadata.schema);

  useEffect(() => () => { requestId.current += 1; }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setDiagram(null);
    setError('');
    setHoverState(null);
    setPinned(null);
    setCollapseJunctionTables(false);
    setShowAllLines(false);
    setTableNameFilter('');
    setExactTableFilter(false);
    tableService.getErDiagram(
      String(metadata.connectionId),
      metadata.catalog ?? undefined,
      metadata.schema ?? undefined,
    ).then((result) => {
      if (!cancelled) setDiagram(result);
    }).catch((err: unknown) => {
      if (!cancelled) {
        setDiagram(null);
        setError(err instanceof Error ? err.message : t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FAILED));
      }
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [metadata.catalog, metadata.connectionId, metadata.schema, t]);

  const junctionTables = useMemo(() => {
    const tables = new Set<string>();
    diagram?.relations.forEach((relation) => {
      const id = junctionTableId(relation);
      if (id) tables.add(id);
    });
    return tables;
  }, [diagram]);
  const collapsed = useMemo(
    () => (diagram ? collapseJunctions(diagram, collapseJunctionTables) : null),
    [collapseJunctionTables, diagram],
  );
  const shown = useMemo(
    () => (collapsed ? filterTablesByName(collapsed, tableNameFilter, exactTableFilter) : null),
    [collapsed, exactTableFilter, tableNameFilter],
  );

  useEffect(() => {
    const visible = new Set(shown?.tables.map((table) => tableId(table)) ?? []);
    setHoverState((current) => (current && !visible.has(current.tableId) ? null : current));
    setPinned((current) => (current && !visible.has(current.tableId) ? null : current));
  }, [shown]);

  const placeNodes = useCallback((
    next: ErDiagram,
    useStored: boolean,
    previous = getNodes() as ErTableNodeType[],
    persist = false,
    frame = true,
  ) => {
    const token = ++requestId.current;
    setLayouting(true);
    const expandedIds = new Set(previous.filter((node) => node.data.expanded).map((node) => node.id));
    return layoutDiagram(next.tables, next.relations, expandedIds).then((points) => {
      if (token !== requestId.current) return;
      const stored = useStored ? readStoredLayout(storageKey) : null;
      setHasCustomLayout(persist || hasStoredLayout(stored));
      setNodes(toFlowNodes(next.tables, mergeStoredPositions(points, stored), previous));
      if (persist) {
        writeStoredLayout(storageKey, Object.fromEntries(points.map((point) => [point.id, { x: point.x, y: point.y }])));
      }
      if (frame) setLayoutVersion((version) => version + 1);
    }).finally(() => {
      if (token === requestId.current) setLayouting(false);
    });
  }, [getNodes, setNodes, storageKey]);

  const toggleExpanded = useCallback((nodeId: string) => {
    if (!shown || layouting || exporting) return;
    const previous = (getNodes() as ErTableNodeType[]).map((node) => (
      node.id === nodeId ? { ...node, data: { ...node.data, expanded: !node.data.expanded } } : node
    ));
    // Re-layout with the actual card sizes; saved collapsed positions would overlap.
    void placeNodes(shown, false, previous, true);
  }, [exporting, getNodes, layouting, placeNodes, shown]);

  useEffect(() => {
    if (!shown || shown.tables.length === 0) {
      requestId.current += 1;
      setNodes([]);
      setLayouting(false);
      return;
    }
    void placeNodes(shown, true);
  }, [placeNodes, setNodes, shown]);

  const linkedColumns = useMemo(() => {
    const linked = new Set<string>();
    shown?.relations.forEach((relation) => {
      sideColumns(relation, 'from').forEach((column) => linked.add(columnKey(relationFromId(relation), column)));
      sideColumns(relation, 'to').forEach((column) => linked.add(columnKey(relationToId(relation), column)));
    });
    return linked;
  }, [shown]);

  const active = hover ?? pinned;
  const activeRelations = useMemo(
    () => (shown && active ? shown.relations.filter((relation) => relationTouches(relation, active)) : []),
    [active, shown],
  );
  const highlightedColumns = useMemo(() => {
    const highlighted = new Set<string>();
    activeRelations.forEach((relation) => {
      sideColumns(relation, 'from').forEach((column) => highlighted.add(columnKey(relationFromId(relation), column)));
      sideColumns(relation, 'to').forEach((column) => highlighted.add(columnKey(relationToId(relation), column)));
    });
    return highlighted;
  }, [activeRelations]);

  const rects = useMemo<DiagramRect[]>(() => nodes.map((node) => ({
    id: node.id,
    x: node.position.x,
    y: node.position.y,
    width: node.width ?? CARD_WIDTH,
    height: cardHeight(node.data.table.columns.length, node.data.expanded),
  })), [nodes]);

  useEffect(() => {
    setEdges(buildDiagramEdges(
      nodes.map((node) => ({
        id: node.id,
        position: node.position,
        width: node.width,
        expanded: node.data.expanded,
        columns: node.data.table.columns,
        tableKey: tableId(node.data.table),
      })),
      shown?.relations ?? [],
      active,
      showAllLines,
      linkedColumns,
      highlightedColumns,
    ));
  }, [active, highlightedColumns, linkedColumns, nodes, setEdges, showAllLines, shown]);

  const togglePin = useCallback((focus: ColumnFocus) => {
    setPinned((current) => (
      current?.tableId === focus.tableId && current.column === focus.column ? null : focus
    ));
  }, []);
  const setHover = useCallback((focus: ColumnFocus) => {
    if (draggingRef.current) return;
    setHoverState(focus);
  }, []);
  const clearHover = useCallback((focus: ColumnFocus) => {
    if (draggingRef.current) return;
    setHoverState((current) => (
      current?.tableId === focus.tableId && current.column === focus.column ? null : current
    ));
  }, []);

  const openTableData = useCallback((table: ErTable) => {
    const { catalog, schema } = tableLocation(table, metadata);
    const connectionId = Number(metadata.connectionId);
    openTab({
      id: `table-${connectionId}-${catalog}-${schema}-${table.name}`,
      name: table.name,
      type: 'table',
      content: '',
      metadata: {
        connectionId,
        dbType: metadata.dbType,
        connectionName: metadata.connectionName,
        databaseName: catalog || schema || null,
        schemaName: schema || null,
        objectName: table.name,
        objectType: 'table',
        catalog: catalog || undefined,
        schema: schema || undefined,
      },
    });
  }, [metadata, openTab]);

  const openTableDdl = useCallback(async (table: ErTable) => {
    const { catalog, schema } = tableLocation(table, metadata);
    const connectionId = Number(metadata.connectionId);
    const consoleTabs = tabs.filter((tab) => (
      tab.type === 'file'
      && (tab.metadata as ConsoleTabMetadata | undefined)?.connectionId === connectionId
    ));
    const reuse = tableDblClickConsoleTarget === TableDblClickConsoleTargetEnum.REUSE && consoleTabs.length > 0;
    const tabId = reuse
      ? consoleTabs[0].id
      : `ddl-${connectionId}-${catalog}-${schema}-${table.name}`;
    const consoleMetadata = {
      connectionId,
      connectionName: metadata.connectionName,
      databaseName: catalog || schema || null,
      schemaName: schema || null,
      dbType: metadata.dbType,
    };
    if (reuse) {
      updateTabMetadata(tabId, consoleMetadata);
      switchTab(tabId);
    } else {
      openTab({
        id: tabId,
        name: table.name,
        type: 'file',
        content: '',
        metadata: consoleMetadata,
      });
    }
    try {
      const ddl = await tableService.getTableDdl(String(connectionId), table.name, catalog, schema);
      updateTabContent(tabId, ddl);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : t(I18N_KEYS.EXPLORER.LOAD_DDL_FAILED);
      updateTabContent(tabId, `-- ${message}\n`);
      toast.error(message);
    }
  }, [metadata, openTab, switchTab, t, tableDblClickConsoleTarget, tabs, toast, updateTabContent, updateTabMetadata]);

  const openExternalErDiagram = useCallback((table: ErTable) => {
    if (!table.external) return;
    const catalog = table.catalog || '';
    const schema = table.schema || '';
    if (catalog === (metadata.catalog || '') && schema === (metadata.schema || '')) return;
    const scope = schema || catalog;
    if (!scope) return;
    const connectionId = Number(metadata.connectionId);
    openTab({
      id: `er-${connectionId}-${catalog}-${schema}`,
      name: `ER ${scope}`,
      type: 'er',
      content: '',
      metadata: {
        connectionId,
        dbType: metadata.dbType,
        connectionName: metadata.connectionName,
        databaseName: catalog || schema || null,
        schemaName: schema || null,
        catalog: catalog || undefined,
        schema: schema || undefined,
      },
    });
  }, [metadata, openTab]);

  const interaction = useMemo(() => ({
    linked: linkedColumns,
    highlighted: highlightedColumns,
    junctionTables,
    active,
    draggingRef,
    togglePin,
    setHover,
    clearHover,
    busy: layouting || exporting,
    toggleExpanded,
    openData: openTableData,
    openDdl: openTableDdl,
    openExternal: openExternalErDiagram,
  }), [
    active,
    clearHover,
    exporting,
    layouting,
    toggleExpanded,
    highlightedColumns,
    junctionTables,
    linkedColumns,
    openExternalErDiagram,
    openTableData,
    openTableDdl,
    setHover,
    togglePin,
  ]);

  const frameNodes = useCallback((current: ErTableNodeType[]) => {
    const pane = flowRef.current;
    if (!pane || current.length === 0) return Promise.resolve(false);
    const bounds = layoutBounds(current.map((node) => ({
      x: node.position.x,
      y: node.position.y,
      width: node.measured?.width || node.width || CARD_WIDTH,
      height: node.measured?.height || node.height || cardHeight(node.data.table.columns.length, node.data.expanded),
    })));
    const viewport = viewportForDiagram(bounds, pane.clientWidth, pane.clientHeight);
    if (!viewport) return Promise.resolve(false);
    return setViewport(viewport, { duration: 0 }).then((applied) => applied, () => false);
  }, [setViewport]);

  const fitToWindow = useCallback(() => {
    void frameNodes(getNodes() as ErTableNodeType[]);
  }, [frameNodes, getNodes]);

  useLayoutEffect(() => {
    if (layouting || nodes.length === 0 || framedToken.current === layoutVersion) return;
    setFrameReady(false);
    let stopped = false;
    let attempts = 0;
    let frame = 0;
    const apply = () => {
      if (stopped || framedToken.current === layoutVersion) return;
      void frameNodes(nodes).then((fitted) => {
        if (stopped || framedToken.current === layoutVersion) return;
        if (fitted) {
          framedToken.current = layoutVersion;
          setFrameReady(true);
          return;
        }
        attempts += 1;
        if (attempts < 8) frame = requestAnimationFrame(apply);
      });
    };
    apply();
    const pane = flowRef.current;
    const observer = pane ? new ResizeObserver(() => apply()) : null;
    if (pane && observer) observer.observe(pane);
    return () => {
      stopped = true;
      cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [frameNodes, layoutVersion, layouting, nodes]);

  const resetLayout = () => {
    if (!shown || shown.tables.length === 0) return;
    clearStoredLayout(storageKey);
    void placeNodes(shown, false, getNodes() as ErTableNodeType[], false, false);
  };

  const onNodeDragStart = () => {
    draggingRef.current = true;
    setHoverState(null);
  };

  const onNodeDragStop = (_event: unknown, node: ErTableNodeType) => {
    draggingRef.current = false;
    const updates: Record<string, { x: number; y: number }> = {};
    getNodes().forEach((item) => {
      const position = item.id === node.id ? node.position : item.position;
      updates[item.id] = { x: position.x, y: position.y };
    });
    writeStoredLayout(storageKey, updates);
    setHasCustomLayout(true);
  };

  const exportImage = async (format: Exclude<ErDiagramExportFormat, 'json' | 'mmd'>) => {
    const viewport = flowRef.current?.querySelector('.react-flow__viewport');
    if (!(viewport instanceof HTMLElement) || exporting || layouting) return;
    const currentNodes = getNodes();
    if (currentNodes.length === 0) return;
    const bounds = getNodesBounds(currentNodes);
    if (bounds.width <= 0 || bounds.height <= 0) return;
    const background = getComputedStyle(document.documentElement).getPropertyValue('--bg-main').trim() || '#f4f7fb';
    setExporting(true);
    try {
      const diagram = {
        tables: vectorTablesFromNodes(currentNodes as ErTableNodeType[], {
          linked: linkedColumns,
          highlighted: highlightedColumns,
          junctions: junctionTables,
          external: t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXTERNAL),
          junction: t(I18N_KEYS.EXPLORER.ER_DIAGRAM_JUNCTION),
          expand: (hidden: number) => t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPAND_COLUMNS, { count: hidden }),
          collapse: t(I18N_KEYS.EXPLORER.ER_DIAGRAM_COLLAPSE_COLUMNS),
        }),
        edges: collectVectorEdges(viewport, edges),
        theme: vectorTheme(background),
      };
      const blob = format === 'png'
        ? await renderErDiagramPng(viewport, bounds, background)
        : format === 'pdf'
          ? await renderErDiagramPdf(diagram)
          : renderErDiagramSvg(diagram);
      const name = metadata.databaseName || metadata.schemaName || metadata.connectionName || 'er-diagram';
      downloadBlob(blob, erDiagramFilename(name, format));
      toast.success(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORTED));
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_FAILED));
    } finally {
      setExporting(false);
    }
  };

  const exportJson = () => {
    if (!shown || exporting || layouting) return;
    const name = metadata.databaseName || metadata.schemaName || metadata.connectionName || 'er-diagram';
    try {
      const json = renderErDiagramJson(toErDiagramDocument(shown));
      downloadBlob(new Blob([json], { type: 'application/json;charset=utf-8' }), erDiagramFilename(name, 'json'));
      toast.success(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORTED));
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_FAILED));
    }
  };

  const exportCsv = () => {
    if (!shown || exporting || layouting) return;
    const name = metadata.databaseName || metadata.schemaName || metadata.connectionName || 'er-diagram';
    try {
      const csv = renderErDiagramCsv(toErDiagramDocument(shown), erDiagramCsvHeaders((key) => t(key)));
      const type = 'text/csv;charset=utf-8';
      downloadBlob(new Blob([csv.tables], { type }), erDiagramCsvFilename(name, 'tables'));
      downloadBlob(new Blob([csv.relations], { type }), erDiagramCsvFilename(name, 'relations'));
      toast.success(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORTED));
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_FAILED));
    }
  };

  const exportMermaid = () => {
    if (!shown || exporting || layouting) return;
    const name = metadata.databaseName || metadata.schemaName || metadata.connectionName || 'er-diagram';
    try {
      const text = renderErDiagramMermaid(toErDiagramDocument(shown));
      downloadBlob(new Blob([text], { type: 'text/plain;charset=utf-8' }), erDiagramFilename(name, 'mmd'));
      toast.success(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORTED));
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_FAILED));
    }
  };

  const relationSummary = activeRelations.map((relation) => {
    const via = viaTableName(relation);
    const text = `${relationMapping(relation)} ${cardinalityLabel(relation)}`;
    return via ? `${text} ${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_VIA, { table: via })}` : text;
  }).join(' · ');
  const statusText = relationSummary || t(I18N_KEYS.EXPLORER.ER_DIAGRAM_HINT);
  const showCanvas = !loading && !error && !!shown && shown.tables.length > 0;

  return (
    <ErDiagramInteractionContext.Provider value={interaction}>
      <ErDiagramRectsContext.Provider value={rects}>
        <div className="flex h-full min-h-0 flex-col">
          <div className="shrink-0 border-b theme-border text-xs theme-text-secondary">
            <div className="flex min-h-10 flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
              <span className="theme-text-primary font-medium">{t(I18N_KEYS.EXPLORER.ER_DIAGRAM)}</span>
              <span>{metadata.databaseName || metadata.schemaName || metadata.connectionName}</span>
              {shown && (
                <span>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SUMMARY, {
                    tables: shown.tables.length,
                    relations: shown.relations.length,
                  })}
                </span>
              )}
              {diagram?.truncated && (
                <span>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_TRUNCATED, {
                  count: diagram.tables.filter((table) => !table.external).length,
                })}</span>
              )}
              {diagram?.externalTruncated && (
                <span>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXTERNAL_TRUNCATED, {
                  count: diagram.tables.filter((table) => table.external).length,
                })}</span>
              )}
              {junctionTables.size > 0 && (
                <label className="inline-flex items-center gap-1">
                  <input
                    type="checkbox"
                    className="accent-violet-600"
                    checked={collapseJunctionTables}
                    onChange={(event) => setCollapseJunctionTables(event.target.checked)}
                  />
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_COLLAPSE_JUNCTIONS)}
                </label>
              )}
              {diagram && diagram.tables.length > 0 && (
                <>
                  <input
                    type="search"
                    value={tableNameFilter}
                    onChange={(event) => setTableNameFilter(event.target.value)}
                    placeholder={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER)}
                    aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER)}
                    className="h-6 w-52 rounded border theme-border bg-transparent px-2 text-[11px] theme-text-primary outline-none"
                  />
                  <label className="inline-flex items-center gap-1">
                    <input
                      type="checkbox"
                      className="accent-violet-600"
                      checked={exactTableFilter}
                      onChange={(event) => setExactTableFilter(event.target.checked)}
                    />
                    {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EXACT)}
                  </label>
                </>
              )}
              {showCanvas && (
                <button
                  type="button"
                  aria-pressed={showAllLines}
                  className={`rounded border px-2 py-0.5 disabled:opacity-40 ${showAllLines ? 'border-rose-500 text-rose-600' : 'theme-border theme-text-primary'}`}
                  disabled={!shown || shown.relations.length === 0}
                  onClick={() => setShowAllLines((current) => !current)}
                >
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SHOW_ALL_LINES)}
                </button>
              )}
              {showCanvas && (
                <button type="button" className="rounded border theme-border px-2 py-0.5 theme-text-primary" onClick={fitToWindow}>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FIT)}
                </button>
              )}
              {showCanvas && (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <button
                      type="button"
                      className="inline-flex items-center gap-1 rounded border theme-border px-2 py-0.5 theme-text-primary disabled:opacity-40"
                      disabled={exporting || layouting || nodes.length === 0}
                    >
                      {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT)}
                      <ChevronDown className="h-3 w-3" />
                    </button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="start" className="min-w-[8rem]">
                    <DropdownMenuItem className="text-xs" onClick={() => { void exportImage('png'); }}>
                      {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_PNG)}
                    </DropdownMenuItem>
                    <DropdownMenuItem className="text-xs" onClick={() => { void exportImage('svg'); }}>
                      {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_SVG)}
                    </DropdownMenuItem>
                    <DropdownMenuItem className="text-xs" onClick={() => { void exportImage('pdf'); }}>
                      {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_PDF)}
                    </DropdownMenuItem>
                    <DropdownMenuItem className="text-xs" onClick={exportJson}>
                      {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_JSON)}
                    </DropdownMenuItem>
                    <DropdownMenuItem className="text-xs" onClick={exportCsv}>
                      {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_CSV)}
                    </DropdownMenuItem>
                    <DropdownMenuItem className="text-xs" onClick={exportMermaid}>
                      {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_MERMAID)}
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              )}
              {showCanvas && (
                <button
                  type="button"
                  className="rounded border theme-border px-2 py-0.5 theme-text-primary disabled:opacity-40"
                  disabled={!hasCustomLayout || layouting || exporting}
                  onClick={resetLayout}
                >
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_RESET_LAYOUT)}
                </button>
              )}
              {showCanvas && (
                <span className="inline-flex flex-wrap items-center gap-x-3 gap-y-1 border-l theme-border pl-3">
                  <LegendSample mark="one" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_ONE)} color={ER_ROSE} />
                  <LegendSample mark="many" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_MANY)} color={ER_ROSE} />
                  <LegendSample mark="many" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_MANY_TO_MANY)} color={ER_VIOLET} />
                  <LegendSample label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_CROSS)} dashed />
                </span>
              )}
            </div>
            <div className="flex h-7 min-w-0 items-center overflow-hidden border-t theme-border px-3" title={statusText}>
              {activeRelations.length > 0 ? (
                <span className="min-w-0 truncate theme-text-primary">
                  {activeRelations.map((relation, index) => {
                    const via = viaTableName(relation);
                    return (
                      <span key={`${relationMapping(relation)}-${index}`}>
                        {index > 0 ? <span className="px-1.5 theme-text-secondary">·</span> : null}
                        {relationMapping(relation)}
                        {' '}
                        <span className={relation.cardinality === 'MANY_TO_MANY' ? 'text-violet-600' : undefined}>
                          {cardinalityLabel(relation)}
                        </span>
                        {via ? ` ${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_VIA, { table: via })}` : ''}
                      </span>
                    );
                  })}
                </span>
              ) : (
                <span className="min-w-0 truncate">{statusText}</span>
              )}
            </div>
          </div>
          <div className="relative min-h-0 flex-1">
            {loading && (
              <div className="absolute inset-0 z-10 flex items-center justify-center text-sm theme-text-secondary">
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
            {!loading && diagram && diagram.tables.length > 0 && shown && shown.tables.length === 0 && (
              <div className="absolute inset-0 flex items-center justify-center text-sm theme-text-secondary">
                {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EMPTY)}
              </div>
            )}
            {showCanvas && (
              <div ref={flowRef} className="er-diagram-flow h-full min-h-0">
                <ReactFlow
                  colorMode={theme}
                  nodes={nodes}
                  edges={edges}
                  onNodesChange={onNodesChange}
                  onEdgesChange={onEdgesChange}
                  nodeTypes={nodeTypes}
                  edgeTypes={edgeTypes}
                  onNodeDragStart={onNodeDragStart}
                  onNodeDragStop={onNodeDragStop}
                  minZoom={ER_MIN_ZOOM}
                  maxZoom={ER_MAX_ZOOM}
                  deleteKeyCode={null}
                  nodesConnectable={false}
                  nodesDraggable={!layouting && !exporting}
                  elementsSelectable={false}
                  zoomOnDoubleClick={false}
                  className="theme-bg-main"
                  style={{ backgroundColor: 'var(--bg-main)' }}
                >
                  <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="var(--border-color)" />
                  <ErDiagramMiniMap ready={frameReady} disabled={layouting || exporting} />
                  <ErDiagramZoomBar disabled={layouting || exporting} />
                </ReactFlow>
                {layouting && nodes.length === 0 && (
                  <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm theme-text-secondary">
                    {t(I18N_KEYS.EXPLORER.LOADING)}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </ErDiagramRectsContext.Provider>
    </ErDiagramInteractionContext.Provider>
  );
}
