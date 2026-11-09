import { ExplorerNodeType, ExplorerIdPrefix } from '../constants/explorer';
import type { ExplorerNode } from '../types/explorer';
import {
  useTableGroupStore,
  isManualGroup,
  tableGroupScopeKey,
  type TableGroup,
  type TableGroupRule,
} from '../store/tableGroupStore';

/** Table identity belongs to its scope, independent of its display group. */
function makeTableNode(
  folderId: string,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  name: string,
  groupId?: string,
  groupKind?: 'manual' | 'rule',
  comment?: string,
  previous?: ExplorerNode
): ExplorerNode {
  const node: ExplorerNode = {
    ...previous,
    id: `${folderId}${ExplorerIdPrefix.TABLE}${name}`,
    name,
    type: ExplorerNodeType.TABLE,
    connectionId,
    catalog,
    schema,
    groupId,
    groupKind,
    children: previous?.children ?? [],
  };
  // `undefined` keeps whatever the previous node carried; an empty string clears it.
  if (comment !== undefined) {
    if (comment.trim()) {
      node.comment = comment;
    } else {
      delete node.comment;
    }
  }
  return node;
}

function matchString(
  input: string,
  value: string,
  mode: 'prefix' | 'suffix' | 'contains',
  caseSensitive: boolean | undefined
): boolean {
  if (!value.trim()) return false;
  const a = caseSensitive ? input : input.toLowerCase();
  const b = caseSensitive ? value : value.toLowerCase();
  if (mode === 'prefix') return a.startsWith(b);
  if (mode === 'suffix') return a.endsWith(b);
  return a.includes(b);
}

/** Whether a group rule matches a table name. */
export function matchRule(rule: TableGroupRule, tableName: string): boolean {
  switch (rule.type) {
    case 'exact':
      return rule.tableNames.includes(tableName);
    case 'prefix':
      return matchString(tableName, rule.value, 'prefix', rule.caseSensitive);
    case 'suffix':
      return matchString(tableName, rule.value, 'suffix', rule.caseSensitive);
    case 'contains':
      return matchString(tableName, rule.value, 'contains', rule.caseSensitive);
    case 'regex':
      if (!rule.value.trim()) return false;
      try {
        return new RegExp(rule.value, rule.caseSensitive ? '' : 'i').test(tableName);
      } catch {
        return false;
      }
  }
}

/**
 * Build the children of the "Tables" folder: group nodes (each holding their
 * matched tables) plus ungrouped table nodes, sorted alphabetically so groups
 * and tables interleave like Navicat.
 *
 * Reads group definitions synchronously from the global group store, so every
 * table list rendering path (cache hydration, lazy load, refresh) shares it.
 */
export function buildTablesFolderChildren(opts: {
  connectionId: string;
  catalog?: string;
  schema?: string;
  folderId: string;
  tableNames: string[];
  tableComments?: Record<string, string>;
  /** Existing rendered children to retain metadata when only grouping changes. */
  previousChildren?: ExplorerNode[];
}): ExplorerNode[] {
  const { connectionId, catalog, schema, folderId, tableNames, tableComments } = opts;
  const scope = tableGroupScopeKey(connectionId, catalog, schema);
  const groups = useTableGroupStore.getState().groupsByScope[scope] ?? [];
  const previousTables = new Map<string, ExplorerNode>();
  const collectTables = (nodes: ExplorerNode[]) => {
    for (const node of nodes) {
      if (node.type === ExplorerNodeType.GROUP) {
        collectTables(node.children ?? []);
      } else if (
        node.type === ExplorerNodeType.TABLE && node.connectionId === connectionId &&
        (node.catalog ?? '') === (catalog ?? '') && (node.schema ?? '') === (schema ?? '')
      ) {
        previousTables.set(node.name, node);
      }
    }
  };
  collectTables(opts.previousChildren ?? []);

  // Resolve single-parent membership. Explicit (manual) assignments win over
  // rules, because they reflect direct user intent; within the same kind the
  // first matching group in store order wins.
  const manualGroups = groups.filter(isManualGroup);
  const ruleGroups = groups.filter((group) => !isManualGroup(group));
  const tableToGroup = new Map<string, TableGroup>();
  for (const name of tableNames) {
    const group =
      manualGroups.find((g) => matchRule(g.rule, name)) ??
      ruleGroups.find((g) => matchRule(g.rule, name));
    if (group) tableToGroup.set(name, group);
  }

  const groupNodes: ExplorerNode[] = groups.map((group) => {
    const groupId = `${folderId}${ExplorerIdPrefix.GROUP}${group.id}`;
    const kind: 'manual' | 'rule' = isManualGroup(group) ? 'manual' : 'rule';
    const children = tableNames
      .filter((name) => tableToGroup.get(name)?.id === group.id)
      .map((name) => makeTableNode(
        folderId,
        connectionId,
        catalog,
        schema,
        name,
        group.id,
        kind,
        tableComments ? (tableComments[name] ?? '') : previousTables.get(name)?.comment,
        previousTables.get(name)
      ));
    return {
      id: groupId,
      name: group.name,
      type: ExplorerNodeType.GROUP,
      groupId: group.id,
      groupKind: kind,
      connectionId,
      catalog,
      schema,
      children,
    };
  });

  const ungrouped = tableNames
    .filter((name) => !tableToGroup.has(name))
    .map((name) => makeTableNode(
      folderId,
      connectionId,
      catalog,
      schema,
      name,
      undefined,
      undefined,
      tableComments ? (tableComments[name] ?? '') : previousTables.get(name)?.comment,
      previousTables.get(name)
    ));

  const all: ExplorerNode[] = [...groupNodes, ...ungrouped];
  all.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
  return all;
}
