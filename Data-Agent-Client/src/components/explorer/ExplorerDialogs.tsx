import { useTranslation } from 'react-i18next';
import { ConnectionFormModal, type ConnectionFormMode } from '../common/ConnectionFormModal';
import { DriverManageModal } from '../common/DriverManageModal';
import { DeleteConnectionDialog } from './DeleteConnectionDialog';
import { DeleteEntityDialog } from './DeleteEntityDialog';
import { DdlViewerDialog } from './DdlViewerDialog';
import { TableDataDialog } from './TableDataDialog';
import { CreateTableDialog } from './CreateTableDialog';
import { RenameTableDialog } from './RenameTableDialog';
import { TableCommentDialog } from './TableCommentDialog';
import { ExplorerNodeType } from '../../constants/explorer';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { collectDeletableChildren } from '../../utils/treeOperations';
import type { ExplorerNode } from '../../types/explorer';
import type { DbConnection } from '../../types/connection';
import type { NodeDialogState } from '../../hooks/useDialogState';

interface ExplorerDialogsProps {
  // Connection modal
  connectionModalOpen: boolean;
  onConnectionModalOpenChange: (open: boolean) => void;
  connectionModalMode: ConnectionFormMode;
  connectionEditId: number | undefined;
  initialDbType: string | undefined;

  // Driver modal
  driverModalOpen: boolean;
  onDriverModalOpenChange: (open: boolean) => void;
  selectedDriverDbType: string;

  // Delete connection dialog
  deleteConfirmId: number | null;
  onDeleteConfirmIdChange: (id: number | null) => void;
  onConfirmDeleteConnection: (id: number) => void;
  isDeleteConnectionPending: boolean;

  // DDL viewer
  ddlDialogOpen: boolean;
  onDdlDialogOpenChange: (open: boolean) => void;
  ddlConfig: any;

  // Table data viewer
  tableDataDialogOpen: boolean;
  onTableDataDialogOpenChange: (open: boolean) => void;
  selectedTableDataNode: ExplorerNode | null;
  highlightColumn: string | undefined;

  // Entity delete dialog
  deleteState: any;
  onDeleteStateChange: (state: any) => void;
  onConfirmDelete: () => void;

  // Rename table dialog
  renameState: NodeDialogState;
  onRenameStateChange: (state: NodeDialogState) => void;
  onConfirmRename: (newName: string) => void;

  // Table comment dialog
  commentState: NodeDialogState;
  onCommentStateChange: (state: NodeDialogState) => void;
  onConfirmTableComment: (comment: string) => void;

  // Create table dialog
  createTableDialogOpen: boolean;
  onCreateTableDialogOpenChange: (open: boolean) => void;
  setSelectedCreateTableNode: (node: ExplorerNode | null) => void;
  selectedCreateTableNode: ExplorerNode | null;
  connections?: DbConnection[];
  onCreateTableSuccess?: (node: ExplorerNode) => void;

  // Callbacks
  onConnectionSuccess: () => void;
}

export function ExplorerDialogs({
  connectionModalOpen,
  onConnectionModalOpenChange,
  connectionModalMode,
  connectionEditId,
  initialDbType,

  driverModalOpen,
  onDriverModalOpenChange,
  selectedDriverDbType,

  deleteConfirmId,
  onDeleteConfirmIdChange,
  onConfirmDeleteConnection,
  isDeleteConnectionPending,

  ddlDialogOpen,
  onDdlDialogOpenChange,
  ddlConfig,

  tableDataDialogOpen,
  onTableDataDialogOpenChange,
  selectedTableDataNode,
  highlightColumn,

  deleteState,
  onDeleteStateChange,
  onConfirmDelete,

  renameState,
  onRenameStateChange,
  onConfirmRename,

  commentState,
  onCommentStateChange,
  onConfirmTableComment,

  createTableDialogOpen,
  onCreateTableDialogOpenChange,
  setSelectedCreateTableNode,
  selectedCreateTableNode,
  connections,
  onCreateTableSuccess,

  onConnectionSuccess,
}: ExplorerDialogsProps) {
  const { t } = useTranslation();

  return (
    <>
      <ConnectionFormModal
        open={connectionModalOpen}
        onOpenChange={onConnectionModalOpenChange}
        mode={connectionModalMode}
        editId={connectionEditId}
        initialDbType={initialDbType}
        onSuccess={onConnectionSuccess}
      />

      <DriverManageModal
        open={driverModalOpen}
        onOpenChange={onDriverModalOpenChange}
        databaseType={selectedDriverDbType}
        onSelectDriver={() => {}}
      />

      <DeleteConnectionDialog
        open={deleteConfirmId != null}
        onOpenChange={(open) => !open && onDeleteConfirmIdChange(null)}
        connectionId={deleteConfirmId}
        onConfirm={(id) => {
          onConfirmDeleteConnection(id);
          onDeleteConfirmIdChange(null);
        }}
        isPending={isDeleteConnectionPending}
      />

      {ddlConfig && (
        <DdlViewerDialog
          open={ddlDialogOpen}
          onOpenChange={(open) => {
            onDdlDialogOpenChange(open);
            if (!open) {
              onDeleteStateChange({ ...deleteState, selectedDdlNode: null });
            }
          }}
          title={ddlConfig.title}
          displayName={ddlConfig.displayName}
          loadDdl={ddlConfig.loadDdl}
        />
      )}

      {selectedTableDataNode && (
        <TableDataDialog
          open={tableDataDialogOpen}
          onOpenChange={(open) => {
            onTableDataDialogOpenChange(open);
            if (!open) {
              onDeleteStateChange({ ...deleteState, selectedTableDataNode: null, highlightColumn: undefined });
            }
          }}
          title={selectedTableDataNode.type === ExplorerNodeType.TABLE ? t(I18N_KEYS.EXPLORER.TABLE_DATA) : t(I18N_KEYS.EXPLORER.VIEW_DATA_TITLE)}
          displayName={[selectedTableDataNode.catalog, selectedTableDataNode.schema, selectedTableDataNode.tableName || selectedTableDataNode.name].filter(Boolean).join('.')}
          connectionId={Number(selectedTableDataNode.connectionId!)}
          objectName={selectedTableDataNode.tableName || selectedTableDataNode.objectName || selectedTableDataNode.name}
          objectType={selectedTableDataNode.type === ExplorerNodeType.TABLE ? 'table' : selectedTableDataNode.type === ExplorerNodeType.VIEW ? 'view' : 'table'}
          catalog={selectedTableDataNode.catalog}
          schema={selectedTableDataNode.schema}
          highlightColumn={highlightColumn}
        />
      )}

      {selectedCreateTableNode && (
        <CreateTableDialog
          open={createTableDialogOpen}
          onOpenChange={(open) => {
            onCreateTableDialogOpenChange(open);
            if (!open) setSelectedCreateTableNode(null);
          }}
          node={selectedCreateTableNode}
          connections={connections}
          onSuccess={onCreateTableSuccess}
        />
      )}

      {renameState.node && (
        <RenameTableDialog
          open={renameState.isOpen}
          onOpenChange={(open) => {
            if (!open) {
              onRenameStateChange({ node: null, isOpen: false, isPending: false });
            }
          }}
          tableName={renameState.node.name}
          onConfirm={onConfirmRename}
          isPending={renameState.isPending}
        />
      )}

      {commentState.node && (
        <TableCommentDialog
          open={commentState.isOpen}
          onOpenChange={(open) => {
            if (!open) {
              onCommentStateChange({ node: null, isOpen: false, isPending: false });
            }
          }}
          tableName={commentState.node.name}
          initialComment={commentState.node.comment ?? ''}
          onConfirm={onConfirmTableComment}
          isPending={commentState.isPending}
        />
      )}

      {deleteState.type && deleteState.node && (        <DeleteEntityDialog
          open={deleteState.isOpen}
          onOpenChange={(open) => {
            onDeleteStateChange({ ...deleteState, isOpen: open });
            if (!open) {
              onDeleteStateChange({ type: null, node: null, isOpen: false, isPending: false });
            }
          }}
          entityName={deleteState.node.name}
          entityType={deleteState.type}
          onConfirm={onConfirmDelete}
          isPending={deleteState.isPending}
          itemCount={
            deleteState.type === ExplorerNodeType.FOLDER
              ? collectDeletableChildren(deleteState.node.children ?? []).length
              : undefined
          }
        />
      )}
    </>
  );
}
