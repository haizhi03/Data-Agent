import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
import { ErDiagramFocusActions, ErDiagramRelationStatus } from './ErDiagramFocusBar';
import { ErDiagramToolbar, type ErDiagramToolbarExport } from './ErDiagramToolbar';
import { ErRelationEdgeView } from './ErDiagramEdge';
import { ErTableNode, type ErTableNodeType } from './ErDiagramNode';
import { ErDiagramMiniMap } from './ErDiagramMiniMap';
import { ErDiagramTableDialog } from './ErDiagramTableDialog';
import { ErDiagramZoomBar } from './ErDiagramZoomBar';
import { buildDiagramEdges, type ErRelationEdge } from './erDiagramEdges';
import { ErDiagramInteractionContext, ErDiagramRectsContext } from './erDiagramInteraction';
import {
  CARD_WIDTH,
  ER_MAX_ZOOM,
  cardHeight,
  clearStoredLayout,
  shiftCardsForResize,
  hasStoredLayout,
  layoutDiagram,
  layoutStorageKey,
  mergeStoredPositions,
  readStoredLayout,
  toNodeId,
  writeStoredLayout,
  type LayoutPoint,
} from './erDiagramLayout';
import {
  collapseJunctions,
  columnKey,
  filterTablesByIds,
  junctionTableId,
  matchingTableIds,
  relationFromId,
  relationToId,
  relationTouches,
  sideColumns,
  tableId,
  type ColumnFocus,
} from './erDiagramRelations';
import type { DiagramRect } from './erDiagramRoute';
import { toFocusPayload } from './erDiagramFocus';
import { useErDiagramFocus, useErDiagramSelection } from './useErDiagramSelection';
import { useErDiagramViewport } from './useErDiagramViewport';
import { useErQueryFocusStore } from '../../store/erQueryFocusStore';
import {
  DEFAULT_ER_TABLE_CHOICE,
  erDiagramLimitNotice,
  localDrawnTableNames,
  showErDiagramTablePicker,
  uniqueErTableNames,
  type ErDiagramTableChoice,
} from './erDiagramTableSelection';

const EMPTY_TABLE_NAMES: string[] = [];
const nodeTypes = { erTable: ErTableNode };
const edgeTypes = { er: ErRelationEdgeView };

interface ErDiagramTabProps {
  metadata: ErDiagramTabMetadata;
}

function tableLocation(table: ErTable, metadata: ErDiagramTabMetadata) {
  return {
    catalog: table.catalog || metadata.catalog || '',
    schema: table.schema || metadata.schema || '',
  };
}

function toFlowNodes(
  tables: ErTable[],
  points: LayoutPoint[],
  previous: ErTableNodeType[] = [],
  selectedIds: ReadonlySet<string> = new Set(),
): ErTableNodeType[] {
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
      selectable: true,
      selected: selectedIds.has(id),
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

function markEdgesOutsideSearch(edges: ErRelationEdge[], soughtNodeIds: ReadonlySet<string> | null) {
  if (!soughtNodeIds) return edges;
  return edges.map((edge) => {
    if (!edge.data || soughtNodeIds.has(edge.source) || soughtNodeIds.has(edge.target)) return edge;
    return { ...edge, data: { ...edge.data, dimmed: true } };
  });
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
  const { getNodes } = useReactFlow();
  const [diagram, setDiagram] = useState<ErDiagram | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [hover, setHoverState] = useState<ColumnFocus | null>(null);
  const [pinned, setPinned] = useState<ColumnFocus | null>(null);
  const [collapseJunctionTables, setCollapseJunctionTables] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [layouting, setLayouting] = useState(false);
  const [hasCustomLayout, setHasCustomLayout] = useState(false);
  const [tableNameFilter, setTableNameFilter] = useState('');
  const [exactTableFilter, setExactTableFilter] = useState(false);
  // Kept on this mounted tab, so leaving and returning does not fall back to the first 80.
  const [tableChoice, setTableChoice] = useState<ErDiagramTableChoice>(DEFAULT_ER_TABLE_CHOICE);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [catalogNames, setCatalogNames] = useState<string[] | null>(null);
  const [catalogNamesLoading, setCatalogNamesLoading] = useState(false);
  const [catalogNamesError, setCatalogNamesError] = useState('');
  const [nodes, setNodes, onNodesChange] = useNodesState<ErTableNodeType>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<ErRelationEdge>([]);
  const {
    selectedNodeIds,
    setSelectedNodeIds,
    selectedNodeIdsRef,
    onlyTableIds,
    setOnlyTableIds,
    onlyTableKey,
    pinnedLinks,
    setPinnedLinks,
    showAllLines,
    setShowAllLines,
    modifySelection,
    selectAllTables,
    hidePinnedRelation,
    clearPinnedLinks,
    showOnlySelected,
    showAllTables,
  } = useErDiagramSelection(nodes, setNodes);
  const draggingRef = useRef(false);
  const requestId = useRef(0);
  const loadToken = useRef(0);
  const catalogGeneration = useRef(0);
  const catalogRequestStarted = useRef(false);
  const layoutCause = useRef({ collapse: collapseJunctionTables, diagram, only: '' });
  const storageKey = layoutStorageKey(metadata.connectionId, metadata.catalog, metadata.schema);

  useEffect(() => () => { requestId.current += 1; }, []);

  const loadDiagram = useCallback((choice: ErDiagramTableChoice, resetView: boolean) => {
    const token = ++loadToken.current;
    setLoading(true);
    setError('');
    if (resetView) {
      setDiagram(null);
      setHoverState(null);
      setPinned(null);
    }
    const catalog = metadata.catalog || undefined;
    const schema = metadata.schema || undefined;
    const request = choice.mode === 'default'
      ? tableService.getErDiagram(String(metadata.connectionId), catalog, schema)
      : tableService.loadErDiagram(metadata.connectionId, {
        catalog,
        schema,
        all: choice.mode === 'all',
        tables: choice.mode === 'names' ? choice.names : undefined,
      });
    request.then((result) => {
      if (token !== loadToken.current) return;
      setDiagram(result);
      setTableChoice(choice);
      setNodes([]);
      setLayouting(true);
      setLoading(false);
    }).catch((err: unknown) => {
      if (token !== loadToken.current) return;
      const message = err instanceof Error ? err.message : t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FAILED);
      if (resetView) {
        setDiagram(null);
        setError(message);
      } else {
        toast.error(message);
      }
      setLoading(false);
    });
  }, [metadata.catalog, metadata.connectionId, metadata.schema, setNodes, t, toast]);

  const loadDiagramRef = useRef(loadDiagram);
  loadDiagramRef.current = loadDiagram;

  useEffect(() => {
    setTableChoice(DEFAULT_ER_TABLE_CHOICE);
    setHoverState(null);
    setPinned(null);
    setCollapseJunctionTables(false);
    setShowAllLines(false);
    setTableNameFilter('');
    setExactTableFilter(false);
    setPickerOpen(false);
    catalogGeneration.current += 1;
    catalogRequestStarted.current = false;
    setCatalogNames(null);
    setCatalogNamesError('');
    setCatalogNamesLoading(false);
    loadDiagramRef.current(DEFAULT_ER_TABLE_CHOICE, true);
    return () => {
      loadToken.current += 1;
    };
  }, [metadata.catalog, metadata.connectionId, metadata.schema]);

  const drawnLocalNames = useMemo(
    () => (diagram ? localDrawnTableNames(diagram.tables) : []),
    [diagram],
  );

  const openTablePicker = () => {
    setPickerOpen(true);
    if (catalogNames !== null || catalogRequestStarted.current) return;
    const generation = catalogGeneration.current;
    catalogRequestStarted.current = true;
    setCatalogNamesLoading(true);
    setCatalogNamesError('');
    tableService.listTables(
      String(metadata.connectionId),
      metadata.catalog || undefined,
      metadata.schema || undefined,
    ).then((names) => {
      if (generation !== catalogGeneration.current) return;
      setCatalogNames(uniqueErTableNames(names));
    }).catch((err: unknown) => {
      if (generation !== catalogGeneration.current) return;
      catalogRequestStarted.current = false;
      setCatalogNamesError(err instanceof Error ? err.message : t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FAILED));
    }).finally(() => {
      if (generation !== catalogGeneration.current) return;
      setCatalogNamesLoading(false);
    });
  };

  const confirmTableChoice = (choice: ErDiagramTableChoice) => {
    if (choice.mode === 'names' && choice.names.length === 0) return;
    setPickerOpen(false);
    loadDiagramRef.current(choice, false);
  };

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
  const shown = useMemo(() => {
    if (!collapsed) return null;
    if (!onlyTableIds || onlyTableIds.length === 0) return collapsed;
    return filterTablesByIds(collapsed, new Set(onlyTableIds));
  }, [collapsed, onlyTableIds]);
  const nameMatchIds = useMemo(
    () => (collapsed ? matchingTableIds(collapsed, tableNameFilter, exactTableFilter) : null),
    [collapsed, exactTableFilter, tableNameFilter],
  );
  const showCanvas = !error && !!shown && shown.tables.length > 0;
  const {
    flowRef,
    frameFocusRef,
    frameReady,
    minZoom,
    translateExtent,
    fitToWindow,
    requestFrame,
  } = useErDiagramViewport(nodes, layouting, showCanvas);

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
      setNodes(toFlowNodes(
        next.tables,
        mergeStoredPositions(points, stored),
        previous,
        new Set(selectedNodeIdsRef.current),
      ));
      if (persist) {
        writeStoredLayout(storageKey, Object.fromEntries(points.map((point) => [point.id, { x: point.x, y: point.y }])));
      }
      if (frame) requestFrame();
    }).finally(() => {
      if (token === requestId.current) setLayouting(false);
    });
  }, [getNodes, requestFrame, setNodes, storageKey]);

  const toggleExpanded = useCallback((nodeId: string) => {
    if (layouting || exporting) return;
    const current = getNodes() as ErTableNodeType[];
    const target = current.find((node) => node.id === nodeId);
    if (!target) return;
    const expanded = !target.data.expanded;
    const previousHeight = cardHeight(target.data.table.columns.length, target.data.expanded);
    const nextHeight = cardHeight(target.data.table.columns.length, expanded);
    const shifted = shiftCardsForResize(current.map((node) => ({
      id: node.id,
      x: node.position.x,
      y: node.position.y,
      width: node.width ?? CARD_WIDTH,
      height: node.id === nodeId ? nextHeight : (node.height ?? cardHeight(node.data.table.columns.length, node.data.expanded)),
    })), nodeId, previousHeight);
    const byId = new Map(shifted.map((card) => [card.id, card]));
    const moved = shifted.some((card) => {
      const before = current.find((node) => node.id === card.id);
      return !!before && (before.position.x !== card.x || before.position.y !== card.y);
    });
    setNodes(current.map((node) => {
      const card = byId.get(node.id);
      if (!card) return node;
      return {
        ...node,
        position: { x: card.x, y: card.y },
        height: card.height,
        data: node.id === nodeId ? { ...node.data, expanded } : node.data,
      };
    }));
    if (moved) {
      writeStoredLayout(storageKey, Object.fromEntries(shifted.map((card) => [card.id, { x: card.x, y: card.y }])));
      setHasCustomLayout(true);
    }
  }, [exporting, getNodes, layouting, setNodes, storageKey]);

  useEffect(() => {
    const previous = layoutCause.current;
    if (previous.diagram !== diagram && previous.only !== '') {
      layoutCause.current = { collapse: collapseJunctionTables, diagram, only: '' };
      setOnlyTableIds(null);
      return;
    }
    const sameDiagram = previous.diagram === diagram;
    const onlyFolding = sameDiagram
      && previous.collapse !== collapseJunctionTables
      && previous.only === onlyTableKey;
    const enteringOnly = sameDiagram && previous.only === '' && onlyTableKey !== '';
    const changingOnly = sameDiagram && previous.only !== '' && onlyTableKey !== '' && previous.only !== onlyTableKey;
    const leavingOnly = sameDiagram && previous.only !== '' && onlyTableKey === '';
    if (enteringOnly || changingOnly || leavingOnly) setShowAllLines(false);
    frameFocusRef.current = leavingOnly
      ? new Set(previous.only.split('\n').filter(Boolean).map((id) => toNodeId(id)))
      : null;
    layoutCause.current = { collapse: collapseJunctionTables, diagram, only: onlyTableKey };
    if (!shown || shown.tables.length === 0) {
      requestId.current += 1;
      setNodes([]);
      setLayouting(false);
      return;
    }
    void placeNodes(shown, !enteringOnly && !changingOnly, undefined, false, !onlyFolding);
  }, [collapseJunctionTables, diagram, onlyTableKey, placeNodes, setNodes, shown]);

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

  const {
    selectedTables,
    selectedTableKeys,
    focusSplit,
    pinnedKeys,
    pinnedSplit,
    pinnedKeepIds,
    pinCurrentSelection,
  } = useErDiagramFocus(nodes, selectedNodeIds, pinnedLinks, shown?.relations, setPinnedLinks);
  const dimmedTables = useMemo(() => {
    const dimmed = new Set<string>();
    if (pinnedKeepIds) {
      nodes.forEach((node) => {
        const id = tableId(node.data.table);
        if (!pinnedKeepIds.has(id)) dimmed.add(id);
      });
    }
    if (nameMatchIds) {
      nodes.forEach((node) => {
        const id = tableId(node.data.table);
        if (!nameMatchIds.has(id)) dimmed.add(id);
      });
    }
    return dimmed;
  }, [nameMatchIds, nodes, pinnedKeepIds]);
  const soughtNodeIds = useMemo(() => (
    nameMatchIds ? new Set([...nameMatchIds].map((id) => toNodeId(id))) : null
  ), [nameMatchIds]);

  const rects = useMemo<DiagramRect[]>(() => nodes.map((node) => ({
    id: node.id,
    x: node.position.x,
    y: node.position.y,
    width: node.width ?? CARD_WIDTH,
    height: cardHeight(node.data.table.columns.length, node.data.expanded),
  })), [nodes]);

  useEffect(() => {
    setEdges(markEdgesOutsideSearch(buildDiagramEdges(
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
      pinnedLinks ? pinnedLinks.relations : null,
    ), soughtNodeIds));
  }, [active, highlightedColumns, linkedColumns, nodes, pinnedLinks, setEdges, showAllLines, shown, soughtNodeIds]);

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
    dimmedTables,
    soughtTables: nameMatchIds,
    active,
    draggingRef,
    togglePin,
    setHover,
    clearHover,
    busy: layouting || exporting,
    toggleExpanded,
    modifySelection,
    openData: openTableData,
    openDdl: openTableDdl,
    openExternal: openExternalErDiagram,
  }), [
    active,
    clearHover,
    exporting,
    layouting,
    toggleExpanded,
    dimmedTables,
    highlightedColumns,
    junctionTables,
    linkedColumns,
    nameMatchIds,
    openExternalErDiagram,
    openTableData,
    modifySelection,
    openTableDdl,
    setHover,
    togglePin,
  ]);

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

  const exportDiagram = (format: ErDiagramToolbarExport) => {
    if (format === 'png' || format === 'svg' || format === 'pdf') {
      void exportImage(format);
      return;
    }
    if (format === 'json') exportJson();
    else if (format === 'csv') exportCsv();
    else exportMermaid();
  };
  const localTableCount = diagram ? diagram.tables.filter((table) => !table.external).length : 0;
  const limitNotice = erDiagramLimitNotice(tableChoice, diagram?.truncated === true);
  const showTablePicker = showErDiagramTablePicker(diagram?.tableCount);

  return (
    <ErDiagramInteractionContext.Provider value={interaction}>
      <ErDiagramRectsContext.Provider value={rects}>
        <div className="flex h-full min-h-0 flex-col">
          <ErDiagramToolbar
            scopeLabel={metadata.databaseName || metadata.schemaName || metadata.connectionName || ''}
            summary={shown ? { tables: shown.tables.length, relations: shown.relations.length } : null}
            limitNotice={limitNotice}
            localTableCount={localTableCount}
            externalTruncatedCount={diagram?.externalTruncated
              ? diagram.tables.filter((table) => table.external).length
              : null}
            showFilter={!!diagram && diagram.tables.length > 0}
            tableNameFilter={tableNameFilter}
            onTableNameFilterChange={setTableNameFilter}
            exactTableFilter={exactTableFilter}
            onExactTableFilterChange={setExactTableFilter}
            showTablePicker={showTablePicker}
            pickerDisabled={loading}
            onOpenPicker={openTablePicker}
            showCollapseJunctions={junctionTables.size > 0}
            collapseJunctionTables={collapseJunctionTables}
            onCollapseJunctionTablesChange={setCollapseJunctionTables}
            showCanvas={showCanvas}
            showAllLines={showAllLines}
            showAllLinesDisabled={!shown || shown.relations.length === 0}
            onToggleShowAllLines={() => setShowAllLines((current) => !current)}
            exportDisabled={exporting || layouting || nodes.length === 0}
            onExport={exportDiagram}
            resetDisabled={!hasCustomLayout || layouting || exporting}
            onResetLayout={resetLayout}
            status={(
              <ErDiagramRelationStatus
                filterEmpty={nameMatchIds?.size === 0}
                pinnedLinks={pinnedLinks}
                pinnedSplit={pinnedSplit}
                pinnedKeys={pinnedKeys}
                selectedTableKeys={selectedTableKeys}
                activeRelations={activeRelations}
              />
            )}
          />
          <div className="relative min-h-0 flex-1">
            {loading && (
              <div className="theme-bg-main absolute inset-0 z-10 flex items-center justify-center text-sm theme-text-secondary">
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
              <div
                ref={flowRef}
                tabIndex={0}
                className="er-diagram-flow relative h-full min-h-0 outline-none"
                onMouseDown={(event) => {
                  const target = event.target as HTMLElement;
                  if (target.closest('input, textarea, select, button')) return;
                  flowRef.current?.focus({ preventScroll: true });
                }}
                onKeyDownCapture={(event) => {
                  if (event.key.toLowerCase() !== 'a' || event.altKey || !(event.ctrlKey || event.metaKey)) return;
                  const target = event.target as HTMLElement;
                  if (target.closest('input, textarea, select, [contenteditable="true"]')) return;
                  event.preventDefault();
                  selectAllTables();
                }}
              >
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
                  minZoom={minZoom}
                  maxZoom={ER_MAX_ZOOM}
                  translateExtent={translateExtent}
                  deleteKeyCode={null}
                  nodesConnectable={false}
                  nodesDraggable={!layouting && !exporting}
                  elementsSelectable={!layouting && !exporting}
                  selectionKeyCode="Shift"
                  multiSelectionKeyCode={['Control', 'Meta']}
                  onSelectionChange={({ nodes: picked }) => {
                    setSelectedNodeIds(picked.map((node) => node.id));
                  }}
                  onEdgeClick={(_event, edge) => {
                    const key = edge.data?.focusKey;
                    if (!key) return;
                    hidePinnedRelation(key);
                  }}
                  zoomOnDoubleClick={false}
                  className="theme-bg-main"
                  style={{ backgroundColor: 'var(--bg-main)' }}
                >
                  <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="var(--border-color)" />
                  <ErDiagramMiniMap ready={frameReady} disabled={layouting || exporting} />
                  <ErDiagramZoomBar disabled={layouting || exporting} minZoom={minZoom} onFit={fitToWindow} />
                </ReactFlow>
                <ErDiagramFocusActions
                  selectedTables={selectedTables}
                  pinnedLinks={pinnedLinks}
                  onlyTableIds={onlyTableIds}
                  onPinLinks={pinCurrentSelection}
                  onClearLinks={clearPinnedLinks}
                  onShowOnly={showOnlySelected}
                  onShowAll={showAllTables}
                  onQuery={() => {
                    useErQueryFocusStore.getState().attach(
                      toFocusPayload(selectedTables, focusSplit, {
                        connectionId: metadata.connectionId,
                        connectionName: metadata.connectionName,
                        catalogName: metadata.catalog,
                        schemaName: metadata.schema,
                      }),
                      t(I18N_KEYS.EXPLORER.ER_FOCUS_DRAFT),
                    );
                  }}
                />
                {layouting && nodes.length === 0 && (
                  <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm theme-text-secondary">
                    {t(I18N_KEYS.EXPLORER.LOADING)}
                  </div>
                )}
              </div>
            )}
          </div>
          <ErDiagramTableDialog
            open={pickerOpen}
            names={catalogNames ?? EMPTY_TABLE_NAMES}
            namesLoading={catalogNamesLoading}
            namesError={catalogNamesError}
            drawnNames={drawnLocalNames}
            onOpenChange={setPickerOpen}
            onConfirm={confirmTableChoice}
          />
        </div>
      </ErDiagramRectsContext.Provider>
    </ErDiagramInteractionContext.Provider>
  );
}
