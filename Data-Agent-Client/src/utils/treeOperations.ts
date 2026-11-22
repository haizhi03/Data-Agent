import { ExplorerNodeType } from '../constants/explorer';
import type { ExplorerNode } from '../types/explorer';

/**
 * Flatten a folder's children for batch deletion: unwrap group nodes (tables in
 * a group are not direct children) and skip empty placeholders.
 */
export function collectDeletableChildren(nodes: ExplorerNode[]): ExplorerNode[] {
  return nodes.flatMap((node) => {
    if (node.type === ExplorerNodeType.EMPTY) return [];
    if (node.type === ExplorerNodeType.GROUP) {
      return collectDeletableChildren(node.children ?? []);
    }
    return [node];
  });
}

/**
 * Recursively removes a node from the tree by ID.
 * Returns a new array with the node removed.
 *
 * @param nodes - The tree nodes to search
 * @param nodeId - The ID of the node to remove
 * @returns A new tree with the node removed
 */
export function removeNodeById(nodes: ExplorerNode[], nodeId: string): ExplorerNode[] {
  return nodes
    .map((node) => {
      if (node.id === nodeId) {
        return null;
      }
      if (node.children && node.children.length > 0) {
        return { ...node, children: removeNodeById(node.children, nodeId) };
      }
      return node;
    })
    .filter((n): n is ExplorerNode => n !== null);
}

/**
 * Sets or clears the comment of a node by ID. A blank comment removes the
 * field entirely so tree rows stop reserving space for it.
 */
export function updateNodeCommentById(
  nodes: ExplorerNode[],
  nodeId: string,
  comment?: string
): ExplorerNode[] {
  return nodes.map((node) => {
    if (node.id === nodeId) {
      if (comment == null || comment.trim() === '') {
        const next = { ...node };
        delete next.comment;
        return next;
      }
      return { ...node, comment };
    }
    if (node.children && node.children.length > 0) {
      return { ...node, children: updateNodeCommentById(node.children, nodeId, comment) };
    }
    return node;
  });
}

/**
 * Clears all children from a folder node by ID.
 * Used for batch delete operations on folder contents.
 *
 * @param nodes - The tree nodes to search
 * @param folderId - The ID of the folder whose children should be cleared
 * @returns A new tree with the folder's children cleared
 */
export function clearFolderChildren(nodes: ExplorerNode[], folderId: string): ExplorerNode[] {
  return nodes.map((node) => {
    if (node.id === folderId) {
      return { ...node, children: [] };
    }
    if (node.children && node.children.length > 0) {
      return { ...node, children: clearFolderChildren(node.children, folderId) };
    }
    return node;
  });
}
