import { useState, useEffect } from 'react';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { useConnectionTree } from '../../hooks/useConnectionTree';
import { useDialogState } from '../../hooks/useDialogState';
import { useConnectionActions } from '../../hooks/useConnectionActions';
import { useDataViewActions } from '../../hooks/useDataViewActions';
import { useDeleteActions } from '../../hooks/useDeleteActions';
import { useRenameActions } from '../../hooks/useRenameActions';
import { DataAttributes } from '../../constants/dataAttributes';
import { EXPLORER_SQL_OBJECT_CHANGED, ExplorerNodeType, FolderName } from '../../constants/explorer';
import type { ExplorerNode } from '../../types/explorer';
import { ExplorerHeader } from './ExplorerHeader';
import { ExplorerTree } from './ExplorerTree';
import { ExplorerDialogs } from './ExplorerDialogs';

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

  useEffect(() => {
    useWorkspaceStore.getState().fetchSupportedDbTypes();
  }, []);

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
