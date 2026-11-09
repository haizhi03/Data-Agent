import { useCallback } from 'react';
import type React from 'react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { I18N_KEYS } from '../constants/i18nKeys';
import { useToast } from './useToast';
import { tableService } from '../services/table.service';
import { updateNodeCommentById } from '../utils/treeOperations';
import { updateCachedExplorerTableComment } from '../lib/explorerMetadataCache';
import { resolveErrorMessage } from '../lib/errorMessage';
import type { ExplorerNode } from '../types/explorer';
import type { NodeDialogState } from './useDialogState';

interface TableCommentActionsProps {
  commentState: NodeDialogState;
  setCommentState: React.Dispatch<React.SetStateAction<NodeDialogState>>;
  setTreeDataState: (cb: (prev: ExplorerNode[]) => ExplorerNode[]) => void;
}

export function useTableCommentActions({
  commentState,
  setCommentState,
  setTreeDataState,
}: TableCommentActionsProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const queryClient = useQueryClient();

  const handleTableComment = useCallback((node: ExplorerNode) => {
    if (!node.connectionId) return;
    setCommentState({ node, isOpen: true, isPending: false });
  }, [setCommentState]);

  const confirmTableComment = useCallback(async (comment: string) => {
    const { node } = commentState;
    const connectionId = node?.connectionId;
    if (!node || !connectionId) {
      setCommentState({ node: null, isOpen: false, isPending: false });
      return;
    }

    const nextComment = comment.trim();
    if (nextComment === (node.comment ?? '').trim()) {
      setCommentState({ node: null, isOpen: false, isPending: false });
      return;
    }

    setCommentState((prev) => ({ ...prev, isPending: true }));

    try {
      const tableName = node.objectName ?? node.name;
      await tableService.updateTableComment(
        connectionId,
        tableName,
        nextComment,
        node.catalog,
        node.schema,
      );

      setTreeDataState((prev) => updateNodeCommentById(prev, node.id, nextComment));
      updateCachedExplorerTableComment(
        queryClient,
        connectionId,
        node.catalog,
        node.schema,
        tableName,
        nextComment,
      );
      toast.success(t(I18N_KEYS.EXPLORER.TABLE_COMMENT_SAVE_SUCCESS));
      setCommentState({ node: null, isOpen: false, isPending: false });
    } catch (error) {
      console.error('Failed to update table comment:', error);
      toast.error(resolveErrorMessage(error, t(I18N_KEYS.EXPLORER.TABLE_COMMENT_SAVE_FAILED)));
      setCommentState((prev) => ({ ...prev, isPending: false }));
    }
  }, [commentState, queryClient, setCommentState, setTreeDataState, t, toast]);

  return { handleTableComment, confirmTableComment };
}
