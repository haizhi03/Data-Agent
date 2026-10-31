import { useCallback } from 'react';
import type React from 'react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { I18N_KEYS } from '../constants/i18nKeys';
import { useToast } from './useToast';
import { tableService } from '../services/table.service';
import { renameNodeById } from '../utils/treeOperations';
import { renameCachedExplorerTable } from '../lib/explorerMetadataCache';
import { resolveErrorMessage } from '../lib/errorMessage';
import { useTableGroupStore, tableGroupScopeKey } from '../store/tableGroupStore';
import { ExplorerNodeType } from '../constants/explorer';
import type { ExplorerNode } from '../types/explorer';

interface RenameState {
  node: ExplorerNode | null;
  isOpen: boolean;
  isPending: boolean;
}

interface RenameActionsProps {
  setRenameState: React.Dispatch<React.SetStateAction<RenameState>>;
  renameState: RenameState;
  setTreeDataState: (cb: (prev: ExplorerNode[]) => ExplorerNode[]) => void;
  /** Re-derives the Tables folder so grouped nodes pick up the new name. */
  rebuildTablesFolder?: (connectionId: string, catalog?: string, schema?: string) => void;
}

export function useRenameActions({
  setRenameState,
  renameState,
  setTreeDataState,
  rebuildTablesFolder,
}: RenameActionsProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const queryClient = useQueryClient();

  const handleRename = useCallback((node: ExplorerNode) => {
    if (!node.connectionId) return;
    setRenameState({ node, isOpen: true, isPending: false });
  }, [setRenameState]);

  const confirmRename = useCallback(async (newName: string) => {
    const { node } = renameState;
    const trimmedName = newName.trim();
    if (!node || !trimmedName || trimmedName === node.name) {
      setRenameState({ node: null, isOpen: false, isPending: false });
      return;
    }

    setRenameState((prev) => ({ ...prev, isPending: true }));

    try {
      const oldName = node.objectName ?? node.name;
      await tableService.renameTable(
        node.connectionId!,
        oldName,
        trimmedName,
        node.catalog,
        node.schema,
      );

      setTreeDataState((prev: ExplorerNode[]) => renameNodeById(prev, node.id, trimmedName));

      // The metadata cache and manual groups key tables by name, so carry the
      // rename over there; rule groups match by name, so rebuild the folder to
      // reflect any resulting membership change without a full refresh.
      if (node.type === ExplorerNodeType.TABLE) {
        renameCachedExplorerTable(
          queryClient,
          node.connectionId!,
          node.catalog,
          node.schema,
          oldName,
          trimmedName,
        );
        useTableGroupStore.getState().renameTableInGroups(
          tableGroupScopeKey(node.connectionId!, node.catalog, node.schema),
          oldName,
          trimmedName,
        );
        rebuildTablesFolder?.(node.connectionId!, node.catalog, node.schema);
      }
      toast.success(t(I18N_KEYS.EXPLORER.RENAME_TABLE_SUCCESS));
    } catch (error) {
      console.error('Failed to rename table:', error);
      toast.error(resolveErrorMessage(error, t(I18N_KEYS.EXPLORER.RENAME_TABLE_FAILED)));
    } finally {
      setRenameState({ node: null, isOpen: false, isPending: false });
    }
  }, [queryClient, rebuildTablesFolder, renameState, setRenameState, setTreeDataState, t, toast]);

  return { handleRename, confirmRename };
}
