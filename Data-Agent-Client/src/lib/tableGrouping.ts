import { ExplorerNodeType, ExplorerIdPrefix } from '../constants/explorer';
import type { ExplorerNode } from '../types/explorer';
import {
  useTableGroupStore,
  isManualGroup,
  tableGroupScopeKey,
  type TableGroup,
  type TableGroupRule,
} from '../store/tableGroupStore';

/** Build a TABLE node with the given id prefix (folder id or group node id). */
function makeTableNode(
  prefix: string,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  name: string,
  groupId?: string,
  groupKind?: 'manual' | 'rule'
): ExplorerNode {
  const node: ExplorerNode = {
    id: `${prefix}${ExplorerIdPrefix.TABLE}${name}`,
    name,
    type: ExplorerNodeType.TABLE,
    connectionId,
    catalog,
    schema,
    children: [],
  };
  if (groupId && groupKind) {
    node.groupId = groupId;
    node.groupKind = groupKind;
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
}): ExplorerNode[] {
  const { connectionId, catalog, schema, folderId, tableNames } = opts;
  const scope = tableGroupScopeKey(connectionId, catalog, schema);
  const groups = useTableGroupStore.getState().groupsByScope[scope] ?? [];

  // Resolve single-parent membership: first matching group (in order) wins.
  const tableToGroup = new Map<string, TableGroup>();
  for (const name of tableNames) {
    for (const group of groups) {
      if (matchRule(group.rule, name)) {
        tableToGroup.set(name, group);
        break;
      }
    }
  }

  const groupNodes: ExplorerNode[] = groups.map((group) => {
    const groupId = `${folderId}${ExplorerIdPrefix.GROUP}${group.id}`;
    const kind: 'manual' | 'rule' = isManualGroup(group) ? 'manual' : 'rule';
    const children = tableNames
      .filter((name) => tableToGroup.get(name)?.id === group.id)
      .map((name) => makeTableNode(groupId, connectionId, catalog, schema, name, group.id, kind));
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
    .map((name) => makeTableNode(folderId, connectionId, catalog, schema, name));

  const all: ExplorerNode[] = [...groupNodes, ...ungrouped];
  all.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
  return all;
}
