import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { useAuthStore } from '../../store/authStore';
import { useTabStore } from '../../store/tabStore';
import { useToast } from '../../hooks/useToast';
import type { TableTabMetadata } from '../../types/tab';
import { useConnectionTree } from '../../hooks/useConnectionTree';
import { useDialogState } from '../../hooks/useDialogState';
import { useConnectionActions } from '../../hooks/useConnectionActions';
import { useDataViewActions } from '../../hooks/useDataViewActions';
import { useDeleteActions } from '../../hooks/useDeleteActions';
import { useRenameActions } from '../../hooks/useRenameActions';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { DataAttributes } from '../../constants/dataAttributes';
import { EXPLORER_SQL_OBJECT_CHANGED, ExplorerNodeType, FolderName } from '../../constants/explorer';
import type { ExplorerNode, SqlScriptTarget } from '../../types/explorer';
import { ExplorerHeader } from './ExplorerHeader';
import { ExplorerTree, type ExplorerTreeHandle } from './ExplorerTree';
import { ExplorerDialogs } from './ExplorerDialogs';
import { ExportDataDialog } from './ExportDataDialog';
import type { ExportDataParams } from './ExportDataDialog';
import { BatchExportDialog } from './BatchExportDialog';
import type { BatchExportParams } from './BatchExportDialog';
import { BatchImportDialog } from './BatchImportDialog';
import type { BatchImportParams } from './BatchImportDialog';
import { ImportDataDialog } from './ImportDataDialog';
import type { ImportDataParams } from './ImportDataDialog';
import { SchemaDdlGeneratorDialog } from './SchemaDdlGeneratorDialog';
import { RunSqlScriptDialog } from './RunSqlScriptDialog';

export function DatabaseExplorer() {
  const { t } = useTranslation();
  const toast = useToast();
  const { supportedDbTypes, supportedDbTypesLoading, openTab, fetchSupportedDbTypes } = useWorkspaceStore();
  const accessToken = useAuthStore((state) => state.accessToken);
  const {
    connections,
    treeDataState,
    setTreeDataState,
    loadingNodeIds,
    hydrateNodeFromCache,
    loadNodeData,
    refreshNodeById,
    handleDisconnect,
    isConnectionsLoading,
    refetchConnections,
    deleteMutation,
  } = useConnectionTree();

  const [searchTerm, setSearchTerm] = useState('');
  const treeRef = useRef<ExplorerTreeHandle>(null);

  const handleLocateTable = useCallback(async () => {
    const { tabs, activeTabId } = useTabStore.getState();
    const tab = tabs.find((item) => item.id === activeTabId);
    const metadata = tab?.type === 'table' ? tab.metadata as TableTabMetadata | undefined : undefined;
    const catalog = metadata?.catalog || metadata?.databaseName;
    if (!metadata?.connectionId || !catalog || !metadata.objectName) {
      toast.warning(t(I18N_KEYS.EXPLORER.LOCATE_TABLE_NONE));
      return;
    }
    const found = await treeRef.current?.locateTable({
      connectionId: metadata.connectionId,
      catalog,
      schema: metadata.schema || metadata.schemaName,
      objectName: metadata.objectName,
      objectType: metadata.objectType,
    });
    if (!found) {
      toast.warning(t(I18N_KEYS.EXPLORER.LOCATE_TABLE_MISSING));
    }
  }, [t, toast]);

  // Dialog state management
  const dialogState = useDialogState();
  const {
    connectionModalOpen,
    setConnectionModalOpen,
    connectionModalMode,
    setConnectionModalMode,
    connectionEditId,
    setConnectionEditId,
    initialDbType,
    setInitialDbType,
    driverModalOpen,
    setDriverModalOpen,
    selectedDriverDbType,
    setSelectedDriverDbType,
    deleteConfirmId,
    setDeleteConfirmId,
    ddlDialogOpen,
    setDdlDialogOpen,
    selectedDdlNode,
    setSelectedDdlNode,
    tableDataDialogOpen,
    setTableDataDialogOpen,
    selectedTableDataNode,
    setSelectedTableDataNode,
    highlightColumn,
    setHighlightColumn,
    deleteState,
    setDeleteState,
    createTableDialogOpen,
    setCreateTableDialogOpen,
    selectedCreateTableNode,
    setSelectedCreateTableNode,
    renameState,
    setRenameState,
  } = dialogState;

  // Connection actions
  const { openCreateModal, openEditModal } = useConnectionActions({
    setConnectionModalMode,
    setConnectionEditId,
    setInitialDbType,
    setConnectionModalOpen,
  });

  // Data view actions
  const { handleViewDdl, handleGenerateIndexes, handleGenerateSql, handleViewData, handleTableOrViewDoubleClick, handleOpenQueryConsole, handleCreateTable, handleOpenErDiagram, getDdlConfig, resolveConsoleContext } = useDataViewActions({
    setSelectedDdlNode,
    setDdlDialogOpen,
    setTableDataDialogOpen,
    setSelectedTableDataNode,
    setHighlightColumn,
    setCreateTableDialogOpen,
    setSelectedCreateTableNode,
    openTab,
    selectedDdlNode,
    connections,
  });

  // Delete actions
  const { handleDelete, confirmDelete } = useDeleteActions({
    setDeleteState,
    deleteState,
    setTreeDataState,
  });

  // Rename actions
  const { handleRename, confirmRename } = useRenameActions({
    setRenameState,
    renameState,
    setTreeDataState,
  });

  const ddlConfig = getDdlConfig();

  // Export data dialog
  const [exportDialogOpen, setExportDialogOpen] = useState(false);
  const [exportTargetPath, setExportTargetPath] = useState('');
  const [exportParams, setExportParams] = useState<ExportDataParams | null>(null);
  const handleExportData = (node: ExplorerNode) => {
    const conn = node.dbConnection ?? connections.find((c) => String(c.id) === node.connectionId);
    const catalog = node.catalog ?? conn?.database ?? '';
    const tableName = node.objectName || node.name;
    setExportTargetPath(`@${conn?.host ?? ''}/${catalog}/${tableName}`);
    setExportParams({
      connectionId: Number(node.connectionId),
      tableName,
      catalog: catalog || undefined,
      schema: node.schema || undefined,
    });
    setExportDialogOpen(true);
  };

  // Batch export wizard (right-click the tables folder)
  const [batchExportOpen, setBatchExportOpen] = useState(false);
  const [batchExportTargetPath, setBatchExportTargetPath] = useState('');
  const [batchExportParams, setBatchExportParams] = useState<BatchExportParams | null>(null);
  const handleExportTablesWizard = (node: ExplorerNode) => {
    const conn = node.dbConnection ?? connections.find((c) => String(c.id) === node.connectionId);
    const catalog = node.catalog ?? conn?.database ?? '';
    setBatchExportTargetPath(`@${conn?.host ?? ''}/${catalog}`);
    setBatchExportParams({
      connectionId: Number(node.connectionId),
      catalog: catalog || undefined,
      schema: node.schema || undefined,
    });
    setBatchExportOpen(true);
  };

  // Batch import wizard (right-click the tables folder)
  const [batchImportOpen, setBatchImportOpen] = useState(false);
  const [batchImportParams, setBatchImportParams] = useState<BatchImportParams | null>(null);
  const [batchImportTargetNodeId, setBatchImportTargetNodeId] = useState<string | null>(null);
  const handleImportTablesWizard = (node: ExplorerNode) => {
    const conn = node.dbConnection ?? connections.find((c) => String(c.id) === node.connectionId);
    const catalog = node.catalog ?? conn?.database ?? '';
    setBatchImportParams({
      connectionId: Number(node.connectionId),
      catalog: catalog || undefined,
      schema: node.schema || undefined,
    });
    setBatchImportTargetNodeId(node.id ?? null);
    setBatchImportOpen(true);
  };

  // Import data dialog
  const [importDialogOpen, setImportDialogOpen] = useState(false);
  const [importTargetPath, setImportTargetPath] = useState('');
  const [importParams, setImportParams] = useState<ImportDataParams | null>(null);
  const handleImportData = (node: ExplorerNode) => {
    const conn = node.dbConnection ?? connections.find((c) => String(c.id) === node.connectionId);
    const catalog = node.catalog ?? conn?.database ?? '';
    const tableName = node.objectName || node.name;
    setImportTargetPath(`@${conn?.host ?? ''}/${catalog}/${tableName}`);
    setImportParams({
      connectionId: Number(node.connectionId),
      tableName,
      catalog: catalog || undefined,
      schema: node.schema || undefined,
    });
    setImportDialogOpen(true);
  };

  // SQL script (right-click a database/schema node): run a .sql file, or generate DDL.
  const [sqlGeneratorOpen, setSqlGeneratorOpen] = useState(false);
  const [runSqlScriptOpen, setRunSqlScriptOpen] = useState(false);
  const [sqlScriptTarget, setSqlScriptTarget] = useState<SqlScriptTarget | null>(null);

  const buildSqlScriptTarget = (node: ExplorerNode): SqlScriptTarget | null => {
    const context = resolveConsoleContext(node);
    if (!context) return null;
    const conn = node.dbConnection ?? connections.find((c) => c.id === context.connectionId);
    const targetPath = [`@${conn?.host ?? ''}`, context.databaseName, context.schemaName]
      .filter(Boolean).join('/');
    const scope =
      node.type === ExplorerNodeType.FOLDER && node.folderName === FolderName.TABLES ? 'tables'
      : node.type === ExplorerNodeType.FOLDER && node.folderName === FolderName.VIEWS ? 'views'
      : node.type === ExplorerNodeType.FOLDER && node.folderName === FolderName.ROUTINES ? 'routines'
      : 'all';
    return {
      connectionId: context.connectionId,
      connectionName: context.connectionName,
      databaseName: context.databaseName,
      schemaName: context.schemaName,
      dbType: context.dbType,
      targetPath,
      scope,
    };
  };

  const handleSqlGenerator = (node: ExplorerNode) => {
    // Index nodes and the "indexes" folder have no backend DDL endpoint, so build
    // CREATE INDEX statements client-side from index metadata.
    if (node.type === ExplorerNodeType.INDEX
      || (node.type === ExplorerNodeType.FOLDER && node.folderName === FolderName.INDEXES)) {
      void handleGenerateIndexes(node);
      return;
    }
    // Database/schema/folder nodes open the multi-object DDL generator dialog.
    const target = buildSqlScriptTarget(node);
    if (!target) return;
    setSqlScriptTarget(target);
    setSqlGeneratorOpen(true);
  };

  const handleRunSqlScript = (node: ExplorerNode) => {
    const target = buildSqlScriptTarget(node);
    if (!target) return;
    setSqlScriptTarget(target);
    setRunSqlScriptOpen(true);
  };

  useEffect(() => {
    if (!accessToken) return;
    fetchSupportedDbTypes();
  }, [accessToken, fetchSupportedDbTypes]);

  useEffect(() => {
    const onSqlObjectChanged = (event: Event) => {
      const detail = (event as CustomEvent<{
        connectionId: number;
        catalog?: string | null;
        schema?: string | null;
        objectType: string;
      }>).detail;
      const folderByType: Record<string, FolderName> = {
        TABLE: FolderName.TABLES,
        VIEW: FolderName.VIEWS,
        FUNCTION: FolderName.ROUTINES,
        PROCEDURE: FolderName.ROUTINES,
        TRIGGER: FolderName.TRIGGERS,
        SEQUENCE: FolderName.SEQUENCES,
        CONSTRAINT: FolderName.CONSTRAINTS,
      };
      const folderName = folderByType[detail.objectType];
      const nodes: ExplorerNode[] = [];
      const collect = (items: ExplorerNode[]) => {
        for (const item of items) {
          nodes.push(item);
          if (item.children) collect(item.children);
        }
      };
      collect(treeDataState);
      const matchingScope = (node: ExplorerNode) =>
        node.connectionId === String(detail.connectionId)
        && (node.catalog ?? '') === (detail.catalog ?? '');
      const folder = nodes.find(node =>
        node.type === ExplorerNodeType.FOLDER && node.folderName === folderName
        && matchingScope(node) && (node.schema ?? '') === (detail.schema ?? ''));
      const schema = nodes.find(node =>
        node.type === ExplorerNodeType.SCHEMA && matchingScope(node)
        && node.name === detail.schema);
      const target = folder ?? schema;
      if (target) void refreshNodeById(target.id);
    };
    window.addEventListener(EXPLORER_SQL_OBJECT_CHANGED, onSqlObjectChanged);
    return () => window.removeEventListener(EXPLORER_SQL_OBJECT_CHANGED, onSqlObjectChanged);
  }, [treeDataState, refreshNodeById]);

  return (
    <div
      className="flex h-full flex-col overflow-hidden"
      {...{ [DataAttributes.EXPLORER_TREE]: true }}
      onContextMenu={(e) => e.preventDefault()}
    >
      <ExplorerHeader
        searchTerm={searchTerm}
        onSearchChange={setSearchTerm}
        isLoading={isConnectionsLoading}
        onRefresh={refetchConnections}
        supportedDbTypes={supportedDbTypes}
        isDbTypesLoading={supportedDbTypesLoading}
        onRetryDbTypes={() => { void fetchSupportedDbTypes(); }}
        onAddDatabase={openCreateModal}
        onCollapseDatabases={() => treeRef.current?.collapseDatabases()}
        onExpandDatabases={() => { void treeRef.current?.expandDatabases(); }}
        onLocateTable={() => { void handleLocateTable(); }}
        onManageDriver={(dbType) => {
          setSelectedDriverDbType(dbType);
          setDriverModalOpen(true);
        }}
      />

      <div className="flex-1 min-h-0 px-2 pb-2 overflow-hidden">
        <ExplorerTree
          ref={treeRef}
          data={treeDataState}
          searchTerm={searchTerm}
          isLoading={isConnectionsLoading}
          loadingNodeIds={loadingNodeIds}
          onHydrateFromCache={hydrateNodeFromCache}
          onLoadData={loadNodeData}
          onDisconnect={handleDisconnect}
          onEditConnection={openEditModal}
          onDeleteConnection={(id) => setDeleteConfirmId(id)}
          onViewDdl={handleViewDdl}
          onViewData={handleViewData}
          onTableOrViewDoubleClick={handleTableOrViewDoubleClick}
          onRename={handleRename}
          onDelete={handleDelete}
          onOpenQueryConsole={handleOpenQueryConsole}
          onRunSqlScript={handleRunSqlScript}
          onSqlGenerator={handleSqlGenerator}
          onGenerateSql={handleGenerateSql}
          onCreateTable={handleCreateTable}
          onImportData={handleImportData}
          onExportData={handleExportData}
          onExportTablesWizard={handleExportTablesWizard}
          onImportTablesWizard={handleImportTablesWizard}
          onOpenErDiagram={handleOpenErDiagram}
        />
      </div>

      <ExplorerDialogs
        connectionModalOpen={connectionModalOpen}
        onConnectionModalOpenChange={setConnectionModalOpen}
        connectionModalMode={connectionModalMode}
        connectionEditId={connectionEditId}
        initialDbType={initialDbType}
        driverModalOpen={driverModalOpen}
        onDriverModalOpenChange={setDriverModalOpen}
        selectedDriverDbType={selectedDriverDbType}
        deleteConfirmId={deleteConfirmId}
        onDeleteConfirmIdChange={setDeleteConfirmId}
        onConfirmDeleteConnection={(id) => {
          deleteMutation.mutate(id);
          setDeleteConfirmId(null);
        }}
        isDeleteConnectionPending={deleteMutation.isPending}
        ddlDialogOpen={ddlDialogOpen}
        onDdlDialogOpenChange={setDdlDialogOpen}
        ddlConfig={ddlConfig}
        tableDataDialogOpen={tableDataDialogOpen}
        onTableDataDialogOpenChange={setTableDataDialogOpen}
        selectedTableDataNode={selectedTableDataNode}
        highlightColumn={highlightColumn}
        deleteState={deleteState}
        onDeleteStateChange={setDeleteState}
        onConfirmDelete={confirmDelete}
        renameState={renameState}
        onRenameStateChange={setRenameState}
        onConfirmRename={confirmRename}
        createTableDialogOpen={createTableDialogOpen}
        onCreateTableDialogOpenChange={setCreateTableDialogOpen}
        setSelectedCreateTableNode={setSelectedCreateTableNode}
        selectedCreateTableNode={selectedCreateTableNode}
        connections={connections}
        onCreateTableSuccess={(node) => {
          if (node?.id) refreshNodeById(node.id);
        }}
        onConnectionSuccess={refetchConnections}
      />

      <ExportDataDialog
        open={exportDialogOpen}
        onOpenChange={setExportDialogOpen}
        targetTablePath={exportTargetPath}
        exportParams={exportParams}
      />

      <BatchExportDialog
        open={batchExportOpen}
        onOpenChange={setBatchExportOpen}
        targetPath={batchExportTargetPath}
        exportParams={batchExportParams}
      />

      <BatchImportDialog
        open={batchImportOpen}
        onOpenChange={setBatchImportOpen}
        importParams={batchImportParams}
        onSuccess={() => {
          if (batchImportTargetNodeId) refreshNodeById(batchImportTargetNodeId);
        }}
      />

      <ImportDataDialog
        open={importDialogOpen}
        onOpenChange={setImportDialogOpen}
        targetTablePath={importTargetPath}
        importParams={importParams}
      />

      <SchemaDdlGeneratorDialog
        open={sqlGeneratorOpen}
        onOpenChange={setSqlGeneratorOpen}
        target={sqlScriptTarget}
      />

      <RunSqlScriptDialog
        open={runSqlScriptOpen}
        onOpenChange={setRunSqlScriptOpen}
        target={sqlScriptTarget}
      />
    </div>
  );
}
