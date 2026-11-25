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
 * Renames a node in the tree by ID.
 * Returns a new array with the node's display name (and objectName, when it
 * tracked the old name) updated. Loaded table descendants also need the new
 * table name for subsequent data/metadata requests and ids for the renamed path.
 *
 * @param nodes - The tree nodes to search
 * @param nodeId - The ID of the node to rename
 * @param newName - The new name for the node
 * @returns A new tree with the node renamed
 */
export function renameNodeById(nodes: ExplorerNode[], nodeId: string, newName: string): ExplorerNode[] {
  return nodes.map((node) => {
    if (node.id === nodeId) {
      const isTable = node.type === ExplorerNodeType.TABLE;
      const newId = isTable && node.name && node.id.endsWith(node.name)
        ? `${node.id.slice(0, -node.name.length)}${newName}` : node.id;
      const renameDescendants = (children: ExplorerNode[]): ExplorerNode[] => children.map((child) => ({
        ...child,
        id: child.id.startsWith(`${node.id}-`) ? `${newId}${child.id.slice(node.id.length)}` : child.id,
        ...(child.tableName === (node.objectName ?? node.name) ? { tableName: newName } : {}),
        ...(child.children ? { children: renameDescendants(child.children) } : {}),
      }));
      return {
        ...node,
        id: newId,
        name: newName,
        objectName: node.objectName != null ? newName : node.objectName,
        ...(isTable && node.children ? { children: renameDescendants(node.children) } : {}),
      };
    }
    if (node.children && node.children.length > 0) {
      return { ...node, children: renameNodeById(node.children, nodeId, newName) };
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
