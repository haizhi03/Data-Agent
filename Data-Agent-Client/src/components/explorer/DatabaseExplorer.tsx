import { useState, useEffect } from 'react';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { useConnectionTree } from '../../hooks/useConnectionTree';
import { useDialogState } from '../../hooks/useDialogState';
import { useConnectionActions } from '../../hooks/useConnectionActions';
import { useDataViewActions } from '../../hooks/useDataViewActions';
import { useDeleteActions } from '../../hooks/useDeleteActions';
import { useRenameActions } from '../../hooks/useRenameActions';
import { DataAttributes } from '../../constants/dataAttributes';
import type { ExplorerNode } from '../../types/explorer';
import { ExplorerHeader } from './ExplorerHeader';
import { ExplorerTree } from './ExplorerTree';
import { ExplorerDialogs } from './ExplorerDialogs';
import { ExportDataDialog } from './ExportDataDialog';
import type { ExportDataParams } from './ExportDataDialog';
import { ImportDataDialog } from './ImportDataDialog';
import type { ImportDataParams } from './ImportDataDialog';

export function DatabaseExplorer() {
  const { supportedDbTypes, openTab } = useWorkspaceStore();
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
  const { handleViewDdl, handleViewData, handleTableOrViewDoubleClick, handleOpenQueryConsole, handleCreateTable, getDdlConfig } = useDataViewActions({
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

  useEffect(() => {
    useWorkspaceStore.getState().fetchSupportedDbTypes();
  }, []);

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
        onAddDatabase={openCreateModal}
        onManageDriver={(dbType) => {
          setSelectedDriverDbType(dbType);
          setDriverModalOpen(true);
        }}
      />

      <div className="flex-1 min-h-0 px-2 pb-2 overflow-hidden">
        <ExplorerTree
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
          onCreateTable={handleCreateTable}
          onImportData={handleImportData}
          onExportData={handleExportData}
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

      <ImportDataDialog
        open={importDialogOpen}
        onOpenChange={setImportDialogOpen}
        targetTablePath={importTargetPath}
        importParams={importParams}
      />
    </div>
  );
}
