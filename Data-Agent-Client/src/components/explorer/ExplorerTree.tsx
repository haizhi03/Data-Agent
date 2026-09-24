import { forwardRef, useImperativeHandle, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Tree as ArboristTree, NodeApi, TreeApi } from 'react-arborist';
import { ExplorerIdPrefix, ExplorerTreeConfig, ExplorerNodeType } from '../../constants/explorer';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { ExplorerTreeNode } from './ExplorerTreeNode';
import type { ExplorerNode } from '../../types/explorer';
import type { ExplorerNodeHydrationState } from '../../hooks/useConnectionTree';

interface ExplorerTreeProps {
  data: ExplorerNode[];
  searchTerm: string;
  isLoading: boolean;
  loadingNodeIds: ReadonlySet<string>;
  onHydrateFromCache: (node: NodeApi<ExplorerNode>) => ExplorerNodeHydrationState;
  onLoadData: (node: NodeApi<ExplorerNode>) => void;
  onDisconnect: (node: NodeApi<ExplorerNode>) => void;
  onEditConnection: (connId: number) => void;
  onDeleteConnection: (connId: number) => void;
  onViewDdl: (node: ExplorerNode) => void;
  onViewData: (node: ExplorerNode, highlightColumn?: string) => void;
  onTableOrViewDoubleClick: (node: ExplorerNode) => void;
  onRename: (node: ExplorerNode) => void;
  onDelete: (node: ExplorerNode, type: ExplorerNodeType) => void;
  onOpenQueryConsole: (node: ExplorerNode) => void;
  onCreateTable: (node: ExplorerNode) => void;
}

export interface ExplorerLocateTarget {
  connectionId: number;
  catalog?: string | null;
  schema?: string | null;
}

export interface ExplorerTreeHandle {
  collapseDatabases: () => void;
  expandDatabases: () => Promise<void>;
  locateTable: (target: ExplorerLocateTarget) => Promise<boolean>;
}

function isDatabaseNode(node: NodeApi<ExplorerNode>) {
  return node.level === 1 && (
    node.data.type === ExplorerNodeType.DB || node.data.type === ExplorerNodeType.SCHEMA
  );
}

async function ensureChildren(node: NodeApi<ExplorerNode>, onHydrateFromCache: ExplorerTreeProps['onHydrateFromCache'], onLoadData: ExplorerTreeProps['onLoadData']) {
  if (node.data.children && node.data.children.length > 0) return;
  const hydration = onHydrateFromCache(node);
  if (hydration !== 'full') {
    await onLoadData(node);
  }
}

function waitForFrame() {
  return new Promise<void>((resolve) => {
    requestAnimationFrame(() => resolve());
  });
}

function findChild(node: NodeApi<ExplorerNode>, type: ExplorerNodeType, name: string | null | undefined) {
  if (!name) return undefined;
  return (node.children ?? []).find((child) => child.data.type === type && child.data.name === name);
}

async function revealNode(
  node: NodeApi<ExplorerNode>,
  onHydrateFromCache: ExplorerTreeProps['onHydrateFromCache'],
  onLoadData: ExplorerTreeProps['onLoadData'],
) {
  node.open();
  await ensureChildren(node, onHydrateFromCache, onLoadData);
  await waitForFrame();
  await waitForFrame();
  return node.tree.get(node.id) ?? node;
}

export const ExplorerTree = forwardRef<ExplorerTreeHandle, ExplorerTreeProps>(function ExplorerTree({
  data,
  searchTerm,
  isLoading,
  loadingNodeIds,
  onHydrateFromCache,
  onLoadData,
  onDisconnect,
  onEditConnection,
  onDeleteConnection,
  onViewDdl,
  onViewData,
  onTableOrViewDoubleClick,
  onRename,
  onDelete,
  onOpenQueryConsole,
  onCreateTable,
}: ExplorerTreeProps, ref) {
  const { t } = useTranslation();
  const treeRef = useRef<TreeApi<ExplorerNode> | undefined>(undefined);

  useImperativeHandle(ref, () => ({
    collapseDatabases() {
      const tree = treeRef.current;
      if (!tree) return;
      const toClose: NodeApi<ExplorerNode>[] = [];
      const walk = (node: NodeApi<ExplorerNode>) => {
        node.children?.forEach(walk);
        if (node.level >= 1 && node.isOpen) toClose.push(node);
      };
      tree.root.children?.forEach(walk);
      toClose.forEach((node) => node.close());
    },
    async expandDatabases() {
      const tree = treeRef.current;
      if (!tree) return;
      const roots = tree.root.children ?? [];
      await Promise.all(roots.map(async (root) => {
        root.open();
        await ensureChildren(root, onHydrateFromCache, onLoadData);
      }));
      await waitForFrame();
      await waitForFrame();
      const databases = (treeRef.current?.root.children ?? []).flatMap((root) => root.children ?? []).filter(isDatabaseNode);
      await Promise.all(databases.map(async (database) => {
        database.open();
        await ensureChildren(database, onHydrateFromCache, onLoadData);
      }));
    },
    async locateTable(target) {
      const tree = treeRef.current;
      if (!tree) return false;
      const connection = (tree.root.children ?? []).find((node) => (
        node.data.dbConnection?.id === target.connectionId
        || node.id === `${ExplorerIdPrefix.CONNECTION}${target.connectionId}`
      ));
      if (!connection) return false;

      const current = await revealNode(connection, onHydrateFromCache, onLoadData);
      const database = findChild(current, ExplorerNodeType.DB, target.catalog)
        ?? findChild(current, ExplorerNodeType.SCHEMA, target.schema || target.catalog);
      if (!database) return false;
      database.select();
      await tree.scrollTo(database.id, 'center');
      return true;
    },
  }), [onHydrateFromCache, onLoadData]);

  const renderNode = ({ node, style, dragHandle }: { node: NodeApi<ExplorerNode>; style: React.CSSProperties; dragHandle?: unknown }) => {
    return (
      <ExplorerTreeNode
        node={node}
        style={style}
        dragHandle={dragHandle as React.RefObject<HTMLDivElement>}
        isLoading={loadingNodeIds.has(node.id)}
        onHydrateFromCache={onHydrateFromCache}
        onLoadData={onLoadData}
        onDisconnect={onDisconnect}
        onEditConnection={onEditConnection}
        onDeleteConnection={onDeleteConnection}
        onViewDdl={onViewDdl}
        onViewData={onViewData}
        onTableOrViewDoubleClick={onTableOrViewDoubleClick}
        onRename={onRename}
        onDelete={onDelete}
        onOpenQueryConsole={onOpenQueryConsole}
        onCreateTable={onCreateTable}
      />
    );
  };

  return (
    <div className="flex-1 min-h-0 overflow-hidden">
      {data.length === 0 && !isLoading ? (
        <div className="p-4 text-center text-xs theme-text-secondary opacity-60">{t(I18N_KEYS.COMMON.NO_CONNECTIONS)}</div>
      ) : (
        <div className="h-full overflow-y-auto pr-1">
          <ArboristTree
            ref={treeRef}
            data={data}
            openByDefault={false}
            width="100%"
            height={ExplorerTreeConfig.HEIGHT}
            indent={ExplorerTreeConfig.INDENT}
            rowHeight={ExplorerTreeConfig.ROW_HEIGHT}
            searchTerm={searchTerm}
            searchMatch={(node, term) => node.data.name.toLowerCase().includes(term.toLowerCase())}
          >
            {renderNode}
          </ArboristTree>
        </div>
      )}
    </div>
  );
});
