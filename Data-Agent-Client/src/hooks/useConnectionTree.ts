import { useState, useMemo, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { NodeApi } from 'react-arborist';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { connectionService } from '../services/connection.service';
import { useAuthStore } from '../store/authStore';
import { useWorkspaceStore } from '../store/workspaceStore';
import { useTableGroupStore } from '../store/tableGroupStore';
import { useToast } from './useToast';
import { ExplorerNodeType, FolderName, ExplorerIdPrefix, QUERY_KEY_CONNECTIONS } from '../constants/explorer';
import type { ExplorerNode } from '../types/explorer';
import {
  createCachedObjectFolders,
  createFolderNode,
  loadDbSchemaFolders,
  loadFolderContents,
  toChildrenOrEmpty,
} from './connectionTreeLoader';
import {
  clearExplorerMetadataForConnection,
  fetchExplorerDatabases,
  fetchExplorerSchemas,
  getCachedExplorerDatabases,
  getCachedExplorerFunctions,
  getCachedExplorerProcedures,
  getCachedExplorerSchemas,
  getCachedExplorerTables,
  getCachedExplorerTriggers,
  getCachedExplorerViews,
} from '../lib/explorerMetadataCache';
import { buildTablesFolderChildren } from '../lib/tableGrouping';

export type { ExplorerNode };
export { ExplorerNodeType, FolderName };

export type ExplorerNodeHydrationState = 'none' | 'full' | 'partial';

function findNode(
  list: ExplorerNode[],
  predicate: (node: ExplorerNode) => boolean
): ExplorerNode | null {
  for (const n of list) {
    if (predicate(n)) return n;
    if (n.children?.length) {
      const found = findNode(n.children, predicate);
      if (found) return found;
    }
  }
  return null;
}

function findTablesFolderNode(
  list: ExplorerNode[],
  connectionId: string,
  catalog: string,
  schema: string
): ExplorerNode | null {
  return findNode(
    list,
    (n) =>
      n.type === ExplorerNodeType.FOLDER &&
      n.folderName === FolderName.TABLES &&
      n.connectionId === connectionId &&
      (n.catalog ?? '') === catalog &&
      (n.schema ?? '') === schema
  );
}

export function useConnectionTree() {
  const { t } = useTranslation();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { supportedDbTypes } = useWorkspaceStore();
  const accessToken = useAuthStore((s) => s.accessToken);

  const { data, isLoading: isConnectionsLoading, refetch: refetchConnections } = useQuery({
    queryKey: QUERY_KEY_CONNECTIONS,
    queryFn: () => connectionService.getConnections(),
    enabled: !!accessToken,
  });

  const connections = useMemo(() => data || [], [data]);

  const treeData = useMemo<ExplorerNode[]>(() => {
    return connections.map((conn) => ({
      id: `${ExplorerIdPrefix.CONNECTION}${conn.id}`,
      name: conn.name,
      type: ExplorerNodeType.ROOT,
      dbConnection: conn,
      children: [],
    }));
  }, [connections]);

  const [treeDataState, setTreeDataState] = useState<ExplorerNode[]>([]);
  const [loadingNodeIds, setLoadingNodeIds] = useState<Set<string>>(() => new Set());

  useEffect(() => {
    setTreeDataState((prev) => {
      if (prev.length === 0) return treeData;
      return treeData.map((newNode) => {
        const existingNode = prev.find((p) => p.id === newNode.id);
        if (existingNode) {
          return {
            ...newNode,
            connectionId: existingNode.connectionId,
            children: existingNode.children,
          };
        }
        return newNode;
      });
    });
  }, [treeData]);

  const deleteMutation = useMutation({
    mutationFn: (id: number) => connectionService.deleteConnection(id),
    onSuccess: (_, id) => {
      clearExplorerMetadataForConnection(queryClient, String(id));
      useTableGroupStore.getState().removeGroupsForConnection(String(id));
      queryClient.invalidateQueries({ queryKey: QUERY_KEY_CONNECTIONS });
    },
  });

  const disconnectMutation = useMutation({
    mutationFn: (connectionId: number) => connectionService.closeConnection(connectionId),
    onSuccess: (_, connectionId) => {
      clearExplorerMetadataForConnection(queryClient, String(connectionId));
      queryClient.invalidateQueries({ queryKey: QUERY_KEY_CONNECTIONS });
    },
  });

  const updateNodeChildren = (nodeId: string, children: ExplorerNode[], connectionId?: string) => {
    setTreeDataState((prev) => {
      const update = (list: ExplorerNode[]): ExplorerNode[] =>
        list.map((n) => {
          if (n.id === nodeId) {
            return { ...n, children, ...(connectionId ? { connectionId } : {}) };
          }
          if (n.children) return { ...n, children: update(n.children) };
          return n;
        });
      return update(prev);
    });
  };

  const setNodeLoading = (nodeId: string, isLoading: boolean) => {
    setLoadingNodeIds((prev) => {
      const next = new Set(prev);
      if (isLoading) {
        next.add(nodeId);
      } else {
        next.delete(nodeId);
      }
      return next;
    });
  };

  const hydrateNodeFromCache = (node: NodeApi<ExplorerNode>): ExplorerNodeHydrationState => {
    const connId =
      node.data.connectionId ||
      (node.data.dbConnection ? String(node.data.dbConnection.id) : undefined);
    if (!connId) return 'none';

    if (node.data.type === ExplorerNodeType.ROOT && node.data.dbConnection) {
      const rootTypeOption = supportedDbTypes.find((opt) => opt.code === node.data.dbConnection?.dbType);
      const rootSchemaOnly = (rootTypeOption?.supportSchema ?? false) && !(rootTypeOption?.supportDatabase ?? true);
      if (rootSchemaOnly) {
        const schemas = getCachedExplorerSchemas(queryClient, connId, undefined);
        if (!schemas || schemas.length === 0) return 'none';
        const childrenNodes: ExplorerNode[] = schemas.map((schemaName) => ({
          id: `${node.id}${ExplorerIdPrefix.SCHEMA}${schemaName}`,
          name: schemaName,
          type: ExplorerNodeType.SCHEMA,
          connectionId: connId,
          dbConnection: node.data.dbConnection,
          catalog: undefined,
          schema: schemaName,
          children: [],
        }));
        updateNodeChildren(node.id, childrenNodes, String(node.data.dbConnection.id));
        return 'full';
      }
      const dbNames = getCachedExplorerDatabases(queryClient, String(node.data.dbConnection.id));
      if (!dbNames || dbNames.length === 0) return 'none';
      const childrenNodes: ExplorerNode[] = dbNames.map((name) => ({
        id: `${node.id}${ExplorerIdPrefix.DB}${name}`,
        name,
        type: ExplorerNodeType.DB,
        connectionId: String(node.data.dbConnection!.id),
        dbConnection: node.data.dbConnection,
        children: [],
      }));
      updateNodeChildren(node.id, childrenNodes, String(node.data.dbConnection.id));
      return 'full';
    }

    if (node.data.type === ExplorerNodeType.DB) {
      const dbName = node.data.name;
      let rootNode = node;
      while (rootNode.parent && rootNode.level > 0) rootNode = rootNode.parent;
      const typeOption = supportedDbTypes.find((opt) => opt.code === rootNode.data.dbConnection?.dbType);
      const supportSchema = typeOption?.supportSchema ?? false;

      if (supportSchema) {
        const schemas = getCachedExplorerSchemas(queryClient, connId, dbName);
        if (!schemas || schemas.length === 0) return 'none';
        const childrenNodes: ExplorerNode[] = schemas.map((schemaName) => ({
          id: `${node.id}${ExplorerIdPrefix.SCHEMA}${schemaName}`,
          name: schemaName,
          type: ExplorerNodeType.SCHEMA,
          connectionId: connId,
          dbConnection: node.data.dbConnection,
          catalog: dbName,
          schema: schemaName,
          children: [],
        }));
        updateNodeChildren(node.id, childrenNodes);
        return 'full';
      }

      const tables = getCachedExplorerTables(queryClient, connId, dbName) ?? [];
      const views = getCachedExplorerViews(queryClient, connId, dbName) ?? [];
      const functions = getCachedExplorerFunctions(queryClient, connId, dbName);
      const procedures = getCachedExplorerProcedures(queryClient, connId, dbName);
      const triggers = getCachedExplorerTriggers(queryClient, connId, dbName);
      const hasAnyCachedObjectList =
        getCachedExplorerTables(queryClient, connId, dbName) !== undefined
        || getCachedExplorerViews(queryClient, connId, dbName) !== undefined
        || functions !== undefined
        || procedures !== undefined
        || triggers !== undefined;
      if (!hasAnyCachedObjectList) return 'none';
      const folders = createCachedObjectFolders(
        {
          connId,
          parentId: node.id,
          catalog: dbName,
          schema: undefined,
          t,
        },
        tables,
        views,
        functions ?? [],
        procedures ?? [],
        triggers ?? []
      );
      updateNodeChildren(node.id, folders);
      const isFull = functions !== undefined && procedures !== undefined && triggers !== undefined
        && getCachedExplorerTables(queryClient, connId, dbName) !== undefined
        && getCachedExplorerViews(queryClient, connId, dbName) !== undefined;
      return isFull ? 'full' : 'partial';
    }

    if (node.data.type === ExplorerNodeType.SCHEMA) {
      const dbName = node.data.catalog ?? '';
      const schemaName = node.data.name;
      const tables = getCachedExplorerTables(queryClient, connId, dbName, schemaName) ?? [];
      const views = getCachedExplorerViews(queryClient, connId, dbName, schemaName) ?? [];
      const functions = getCachedExplorerFunctions(queryClient, connId, dbName, schemaName);
      const procedures = getCachedExplorerProcedures(queryClient, connId, dbName, schemaName);
      const triggers = getCachedExplorerTriggers(queryClient, connId, dbName, schemaName);
      const hasAnyCachedObjectList =
        getCachedExplorerTables(queryClient, connId, dbName, schemaName) !== undefined
        || getCachedExplorerViews(queryClient, connId, dbName, schemaName) !== undefined
        || functions !== undefined
        || procedures !== undefined
        || triggers !== undefined;
      if (!hasAnyCachedObjectList) return 'none';
      const folders = createCachedObjectFolders(
        {
          connId,
          parentId: node.id,
          catalog: dbName,
          schema: schemaName,
          t,
          isDm: node.data.dbConnection?.dbType?.toLowerCase() === 'dm',
        },
        tables,
        views,
        functions ?? [],
        procedures ?? [],
        triggers ?? []
      );
      updateNodeChildren(node.id, folders);
      const isFull = functions !== undefined && procedures !== undefined && triggers !== undefined
        && getCachedExplorerTables(queryClient, connId, dbName, schemaName) !== undefined
        && getCachedExplorerViews(queryClient, connId, dbName, schemaName) !== undefined;
      return isFull && node.data.dbConnection?.dbType?.toLowerCase() !== 'dm' ? 'full' : 'partial';
    }

    if (node.data.type === ExplorerNodeType.FOLDER && node.data.folderName) {
      if (node.data.folderName === FolderName.TABLES) {
        const tables = getCachedExplorerTables(queryClient, connId, node.data.catalog, node.data.schema);
        if (!tables) return 'none';
        const children = buildTablesFolderChildren({
          connectionId: connId,
          catalog: node.data.catalog,
          schema: node.data.schema,
          folderId: node.id,
          tableNames: tables,
        });
        updateNodeChildren(node.id, toChildrenOrEmpty(children, node.id, t));
        return 'full';
      }

      if (node.data.folderName === FolderName.VIEWS) {
        const views = getCachedExplorerViews(queryClient, connId, node.data.catalog, node.data.schema);
        if (!views) return 'none';
        const children = views.map((name) => ({
          id: `${node.id}${ExplorerIdPrefix.VIEW}${name}`,
          name,
          type: ExplorerNodeType.VIEW,
          connectionId: connId,
          catalog: node.data.catalog,
          schema: node.data.schema,
          children: [],
        }));
        updateNodeChildren(node.id, toChildrenOrEmpty(children, node.id, t));
        return 'full';
      }
    }

    return 'none';
  };

  const loadNodeData = async (node: NodeApi<ExplorerNode>) => {
    if (!node.data.connectionId && node.data.type !== ExplorerNodeType.ROOT) return;

    const connId =
      node.data.connectionId ||
      (node.data.dbConnection ? String(node.data.dbConnection.id) : undefined);
    if (!connId) return;

    setNodeLoading(node.id, true);
    try {
      if (node.data.type === ExplorerNodeType.ROOT && node.data.dbConnection) {
        const typeOption = supportedDbTypes.find((opt) => opt.code === node.data.dbConnection?.dbType);
        // Schema-only databases (e.g. DM): no database layer, schemas hang directly under the connection.
        const schemaOnly = (typeOption?.supportSchema ?? false) && !(typeOption?.supportDatabase ?? true);
        if (schemaOnly) {
          const schemas = await fetchExplorerSchemas(queryClient, connId, undefined);
          const childrenNodes: ExplorerNode[] = schemas.map((schemaName) => ({
            id: `${node.id}${ExplorerIdPrefix.SCHEMA}${schemaName}`,
            name: schemaName,
            type: ExplorerNodeType.SCHEMA,
            connectionId: connId,
            dbConnection: node.data.dbConnection,
            catalog: undefined,
            schema: schemaName,
            children: [],
          }));
          updateNodeChildren(node.id, childrenNodes, String(node.data.dbConnection.id));
          return;
        }
        const dbNames = await fetchExplorerDatabases(queryClient, String(node.data.dbConnection.id));
        const childrenNodes: ExplorerNode[] = dbNames.map((name) => ({
          id: `${node.id}${ExplorerIdPrefix.DB}${name}`,
          name,
          type: ExplorerNodeType.DB,
          connectionId: String(node.data.dbConnection!.id),
          dbConnection: node.data.dbConnection,
          children: [],
        }));
        updateNodeChildren(node.id, childrenNodes, String(node.data.dbConnection.id));
        return;
      }

      if (node.data.type === ExplorerNodeType.DB) {
        const dbName = node.data.name;
        let rootNode = node;
        while (rootNode.parent && rootNode.level > 0) rootNode = rootNode.parent;
        const typeOption = supportedDbTypes.find((opt) => opt.code === rootNode.data.dbConnection?.dbType);
        const supportSchema = typeOption?.supportSchema ?? false;

        if (supportSchema) {
          const schemas = await fetchExplorerSchemas(queryClient, connId, dbName);
          const childrenNodes: ExplorerNode[] = schemas.map((schemaName) => ({
            id: `${node.id}${ExplorerIdPrefix.SCHEMA}${schemaName}`,
            name: schemaName,
            type: ExplorerNodeType.SCHEMA,
            connectionId: connId,
            dbConnection: node.data.dbConnection,
            catalog: dbName,
            schema: schemaName,
            children: [],
          }));
          updateNodeChildren(node.id, childrenNodes);
        } else {
          const folders = await loadDbSchemaFolders({
            connId,
            parentId: node.id,
            catalog: dbName,
            schema: undefined,
            t,
            queryClient,
            isDm: node.data.dbConnection?.dbType?.toLowerCase() === 'dm',
          });
          updateNodeChildren(node.id, folders);
        }
        return;
      }

      if (node.data.type === ExplorerNodeType.SCHEMA) {
        const dbNode = node.parent;
        const dbName = dbNode?.data.type === ExplorerNodeType.DB
          ? dbNode.data.name
          : (node.data.catalog ?? '');
        const schemaName = node.data.name;
        const folders = await loadDbSchemaFolders({
          connId,
          parentId: node.id,
          catalog: dbName,
          schema: schemaName,
          t,
          queryClient,
          isDm: node.data.dbConnection?.dbType?.toLowerCase() === 'dm',
        });
        updateNodeChildren(node.id, folders);
        return;
      }

      if (node.data.type === ExplorerNodeType.FOLDER && node.data.folderName) {
        const catalog = node.data.catalog ?? '';
        const schema = node.data.schema;
        const folderName = node.data.folderName;
        const children = await loadFolderContents(
          {
            connId,
            parentId: node.id,
            catalog,
            schema,
            folderId: node.id,
            tableName: node.data.tableName,
            t,
            queryClient,
          },
          folderName
        );
        updateNodeChildren(node.id, toChildrenOrEmpty(children, node.id, t));
        return;
      }

      if (node.data.type === ExplorerNodeType.TABLE || node.data.type === ExplorerNodeType.VIEW) {
        const catalog = node.data.catalog ?? '';
        const schema = node.data.schema;
        const tableName = node.data.name;
        const columnsFolder = createFolderNode(node.id, FolderName.COLUMNS, connId, catalog, schema, t, tableName);
        const keysFolder = createFolderNode(node.id, FolderName.KEYS, connId, catalog, schema, t, tableName);
        const indexesFolder = createFolderNode(node.id, FolderName.INDEXES, connId, catalog, schema, t, tableName);
        let rootNode = node;
        while (rootNode.parent && rootNode.level > 0) rootNode = rootNode.parent;
        const isDmTable = node.data.type === ExplorerNodeType.TABLE
          && rootNode.data.dbConnection?.dbType?.toLowerCase() === 'dm';
        const constraintsFolder = isDmTable
          ? createFolderNode(node.id, FolderName.CONSTRAINTS, connId, catalog, schema, t, tableName)
          : null;
        updateNodeChildren(node.id, [columnsFolder, keysFolder, indexesFolder, ...(constraintsFolder ? [constraintsFolder] : [])]);
      }
    } catch (err) {
      console.error('Failed to load node data:', err);
      toast.error(t('explorer.load_failed'));
    } finally {
      setNodeLoading(node.id, false);
    }
  };

  const refreshNodeById = async (nodeId: string) => {
    const node = findNode(treeDataState, (n) => n.id === nodeId);
    if (!node) return;
    const connId = node.connectionId ?? (node.dbConnection ? String(node.dbConnection.id) : undefined);
    if (!connId) return;

    setNodeLoading(nodeId, true);
    try {
      if (node.type === ExplorerNodeType.DB) {
        const dbName = node.name;
        const typeOption = supportedDbTypes.find((opt) => opt.code === node.dbConnection?.dbType);
        const supportSchema = typeOption?.supportSchema ?? false;
        if (supportSchema) {
          const schemas = await fetchExplorerSchemas(queryClient, connId, dbName, { force: true });
          const childrenNodes: ExplorerNode[] = schemas.map((schemaName) => ({
            id: `${node.id}${ExplorerIdPrefix.SCHEMA}${schemaName}`,
            name: schemaName,
            type: ExplorerNodeType.SCHEMA,
            connectionId: connId,
            dbConnection: node.dbConnection,
            catalog: dbName,
            schema: schemaName,
            children: [],
          }));
          updateNodeChildren(node.id, childrenNodes);
        } else {
          const folders = await loadDbSchemaFolders({
            connId,
            parentId: node.id,
            catalog: dbName,
            schema: undefined,
            t,
            queryClient,
            force: true,
            isDm: node.dbConnection?.dbType?.toLowerCase() === 'dm',
          });
          updateNodeChildren(node.id, folders);
        }
      } else if (node.type === ExplorerNodeType.SCHEMA) {
        const dbName = node.catalog ?? '';
        const schemaName = node.name;
        const folders = await loadDbSchemaFolders({
          connId,
          parentId: node.id,
          catalog: dbName,
          schema: schemaName,
          t,
          queryClient,
          force: true,
          isDm: node.dbConnection?.dbType?.toLowerCase() === 'dm',
        });
        updateNodeChildren(node.id, folders);
      } else if (node.type === ExplorerNodeType.FOLDER && node.folderName) {
        const catalog = node.catalog ?? '';
        const schema = node.schema;
        const folderName = node.folderName;
        const children = await loadFolderContents(
          {
            connId,
            parentId: node.id,
            catalog,
            schema,
            folderId: node.id,
            tableName: node.tableName,
            t,
            queryClient,
            force: true,
          },
          folderName
        );
        updateNodeChildren(node.id, toChildrenOrEmpty(children, node.id, t));
      }
    } catch (err) {
      console.error('Failed to refresh node:', err);
      toast.error(t('explorer.load_failed'));
    } finally {
      setNodeLoading(nodeId, false);
    }
  };

  // Re-derive the "Tables" folder children after a group definition or membership
  // changes. Uses the already-cached table list, so no network round-trip.
  const rebuildTablesFolder = (connectionId: string, catalog?: string, schema?: string) => {
    const connId = String(connectionId);
    const folder = findTablesFolderNode(treeDataState, connId, catalog ?? '', schema ?? '');
    if (!folder) return;
    const tables = getCachedExplorerTables(queryClient, connId, catalog, schema);
    if (!tables) {
      void refreshNodeById(folder.id);
      return;
    }
    const children = buildTablesFolderChildren({
      connectionId: connId,
      catalog,
      schema,
      folderId: folder.id,
      tableNames: tables,
    });
    updateNodeChildren(folder.id, toChildrenOrEmpty(children, folder.id, t));
  };

  const handleDisconnect = (node: NodeApi<ExplorerNode>) => {
    if (!node.data.connectionId) return;
    const connId = Number(node.data.connectionId);
    if (isNaN(connId)) return;
    disconnectMutation.mutate(connId);
    clearExplorerMetadataForConnection(queryClient, String(connId));
    setTreeDataState((prev) => {
      const update = (list: ExplorerNode[]): ExplorerNode[] =>
        list.map((n) => {
          if (n.id === node.id) return { ...n, connectionId: undefined, children: [] };
          if (n.children) return { ...n, children: update(n.children) };
          return n;
        });
      return update(prev);
    });
  };

  return {
    connections,
    treeDataState,
    setTreeDataState,
    loadingNodeIds,
    hydrateNodeFromCache,
    loadNodeData,
    refreshNodeById,
    rebuildTablesFolder,
    handleDisconnect,
    isConnectionsLoading,
    refetchConnections,
    deleteMutation,
  };
}
