import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useWorkspaceStore } from '../../store/workspaceStore';
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
import { ExplorerHeader } from './ExplorerHeader';
import { ExplorerTree, type ExplorerTreeHandle } from './ExplorerTree';
import { ExplorerDialogs } from './ExplorerDialogs';

export function DatabaseExplorer() {
  const { t } = useTranslation();
  const toast = useToast();
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
  const treeRef = useRef<ExplorerTreeHandle>(null);

  const handleLocateTable = useCallback(async () => {
    const { tabs, activeTabId } = useTabStore.getState();
    const tab = tabs.find((item) => item.id === activeTabId);
    const metadata = tab?.type === 'table' ? tab.metadata as TableTabMetadata | undefined : undefined;
    const catalog = metadata?.catalog || metadata?.databaseName;
    if (!metadata?.connectionId || !catalog) {
      toast.warning(t(I18N_KEYS.EXPLORER.LOCATE_TABLE_NONE));
      return;
    }
    const found = await treeRef.current?.locateTable({
      connectionId: metadata.connectionId,
      catalog,
      schema: metadata.schema || metadata.schemaName,
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
          onCreateTable={handleCreateTable}
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
    </div>
  );
}
