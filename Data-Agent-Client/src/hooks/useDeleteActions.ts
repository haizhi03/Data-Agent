import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { ExplorerNodeType } from '../constants/explorer';
import { I18N_KEYS } from '../constants/i18nKeys';
import { DELETE_DIALOG_CONFIG } from '../constants/deleteConfig';
import { getDeleteService } from '../utils/deleteHandlers';
import { removeNodeById, clearFolderChildren, collectDeletableChildren } from '../utils/treeOperations';
import { useToast } from './useToast';
import {
  clearExplorerMetadataForCatalog,
  invalidateCachedExplorerFolder,
  removeCachedExplorerFunction,
  removeCachedExplorerProcedure,
  removeCachedExplorerTable,
  removeCachedExplorerTrigger,
  removeCachedExplorerView,
} from '../lib/explorerMetadataCache';
import { tableGroupScopeKey, useTableGroupStore } from '../store/tableGroupStore';
import type { ExplorerNode } from '../types/explorer';

interface DeleteActionsProps {
  setDeleteState: (state: any) => void;
  deleteState: any;
  setTreeDataState: (cb: (prev: ExplorerNode[]) => ExplorerNode[]) => void;
}

export function useDeleteActions({
  setDeleteState,
  deleteState,
  setTreeDataState,
}: DeleteActionsProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const queryClient = useQueryClient();

  // Tree rebuilds read from the metadata cache, and manual groups key tables by
  // name; drop the deleted object from both so neither resurrects it.
  const cleanupDeletedNode = useCallback((node: ExplorerNode, type: ExplorerNodeType) => {
    if (!node.connectionId) return;
    const { connectionId, catalog, schema } = node;
    const objectName = node.objectName ?? node.name;
    switch (type) {
      case ExplorerNodeType.TABLE:
        removeCachedExplorerTable(queryClient, connectionId, catalog, schema, objectName);
        useTableGroupStore.getState().removeTableFromGroups(
          tableGroupScopeKey(connectionId, catalog, schema),
          objectName,
        );
        break;
      case ExplorerNodeType.VIEW:
        removeCachedExplorerView(queryClient, connectionId, catalog, schema, objectName);
        break;
      case ExplorerNodeType.FUNCTION:
        removeCachedExplorerFunction(queryClient, connectionId, catalog, schema, objectName);
        break;
      case ExplorerNodeType.PROCEDURE:
        removeCachedExplorerProcedure(queryClient, connectionId, catalog, schema, objectName);
        break;
      case ExplorerNodeType.TRIGGER:
        removeCachedExplorerTrigger(queryClient, connectionId, catalog, schema, objectName);
        break;
      case ExplorerNodeType.DB: {
        // A DB node carries its name, not a catalog, so fall back to it.
        const dbName = catalog ?? node.name;
        clearExplorerMetadataForCatalog(queryClient, connectionId, dbName);
        useTableGroupStore.getState().removeGroupsForCatalog(connectionId, dbName);
        break;
      }
      default:
        break;
    }
  }, [queryClient]);

  const deleteExplorerObject = useCallback(async (
    node: ExplorerNode,
    type: ExplorerNodeType,
    scope: ExplorerNode,
  ) => {
    const deleteService = getDeleteService(type);
    if (!deleteService) throw new Error(`No delete service for ${type}`);

    await deleteService({
      connectionId: scope.connectionId!,
      // Trigger nodes use a display name ("trg (on table)"); the backend needs
      // the raw object name.
      name: node.objectName ?? node.name,
      catalog: scope.catalog,
      schema: scope.schema,
      tableName: node.tableName,
    });
    cleanupDeletedNode(node, type);
  }, [cleanupDeletedNode]);

  const handleDelete = useCallback((node: ExplorerNode, type: ExplorerNodeType) => {
    if (!node.connectionId && type !== ExplorerNodeType.FOLDER) return;
    setDeleteState({ type, node, isOpen: true, isPending: false });
  }, [setDeleteState]);

  const handleFolderDelete = useCallback(async (folderNode: ExplorerNode) => {
    if (!folderNode.connectionId || !folderNode.children) return;

    // Tables nested inside a group are not direct children of the folder; the
    // helper flattens them so "delete all in folder" covers grouped tables too.
    const deletedIds: string[] = [];
    const failed: ExplorerNode[] = [];

    for (const child of collectDeletableChildren(folderNode.children)) {
      const type = child.type as ExplorerNodeType;
      if (!getDeleteService(type)) continue;
      try {
        await deleteExplorerObject(child, type, folderNode);
        deletedIds.push(child.id);
      } catch (err) {
        console.error(`Failed to delete ${child.type} ${child.name}:`, err);
        failed.push(child);
      }
    }

    if (folderNode.folderName) {
      // Force the next expansion to re-read the real list, regardless of whether
      // every object was deleted.
      invalidateCachedExplorerFolder(
        queryClient,
        folderNode.connectionId,
        folderNode.catalog,
        folderNode.schema,
        folderNode.folderName
      );
    }

    if (failed.length === 0) {
      setTreeDataState((prev: ExplorerNode[]) => clearFolderChildren(prev, folderNode.id));
    } else {
      // Keep the folder open and only drop the objects that actually went away,
      // so the user can see (and retry) what is left.
      setTreeDataState((prev: ExplorerNode[]) =>
        deletedIds.reduce((nodes, id) => removeNodeById(nodes, id), prev),
      );
      toast.error(
        t(I18N_KEYS.EXPLORER.DELETE_FOLDER_PARTIAL_FAILED, { count: failed.length }),
      );
    }
  }, [deleteExplorerObject, queryClient, setTreeDataState, t, toast]);

  const confirmDelete = useCallback(async () => {
    const { type, node } = deleteState;
    if (!node || !type) return;

    setDeleteState((prev: any) => ({ ...prev, isPending: true }));

    try {
      if (type === ExplorerNodeType.FOLDER) {
        await handleFolderDelete(node);
      } else {
        await deleteExplorerObject(node, type, node);
        setTreeDataState((prev: ExplorerNode[]) => removeNodeById(prev, node.id));
      }
    } catch (error) {
      console.error(`Failed to delete ${type}:`, error);
      const config = DELETE_DIALOG_CONFIG[type as ExplorerNodeType];
      if (config) alert(t(config.errorKey));
    } finally {
      setDeleteState({ type: null, node: null, isOpen: false, isPending: false });
    }
  }, [
    deleteState,
    deleteExplorerObject,
    handleFolderDelete,
    setTreeDataState,
    setDeleteState,
    t,
  ]);

  return { handleDelete, confirmDelete };
}
