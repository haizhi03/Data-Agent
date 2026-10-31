import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { STORAGE_KEYS } from '../constants/storageKeys';

/**
 * A table group is either a manual group (`exact`) or a rule group (prefix/suffix/contains/regex).
 * Manual groups hold an explicit list of table names; rule groups auto-match tables by name.
 */
export type TableGroupRule =
  | { type: 'exact'; tableNames: string[] }
  | { type: 'prefix'; value: string; caseSensitive?: boolean }
  | { type: 'suffix'; value: string; caseSensitive?: boolean }
  | { type: 'contains'; value: string; caseSensitive?: boolean }
  | { type: 'regex'; value: string; caseSensitive?: boolean };

export type TableGroupRuleType = TableGroupRule['type'];

export interface TableGroup {
  id: string;
  name: string;
  rule: TableGroupRule;
}

export type TableGroupKind = 'manual' | 'rule';

export function isManualGroup(group: TableGroup): boolean {
  return group.rule.type === 'exact';
}

/** Whether a manual group explicitly holds the given table name. */
export function groupContainsTable(group: TableGroup, tableName: string): boolean {
  return group.rule.type === 'exact' && group.rule.tableNames.includes(tableName);
}

/** Scope key that groups are stored under: one connection + catalog + schema. */
export function tableGroupScopeKey(connectionId: string, catalog?: string, schema?: string): string {
  return [connectionId, catalog ?? '', schema ?? ''].join('|');
}

/** Copy of the scope map without the scopes matching the predicate, or null if none match. */
function withoutScopes(
  groupsByScope: Record<string, TableGroup[]>,
  matches: (scope: string) => boolean
): Record<string, TableGroup[]> | null {
  const keys = Object.keys(groupsByScope).filter(matches);
  if (keys.length === 0) return null;
  const next = { ...groupsByScope };
  for (const key of keys) delete next[key];
  return next;
}

/**
 * Rewrite the table names of every manual group holding `tableName`, or return
 * null when no group does. Manual groups store names, so deletes and renames
 * must be carried over or the group goes stale on the next refresh.
 */
function updateManualTableNames(
  groups: TableGroup[],
  tableName: string,
  transform: (tableNames: string[]) => string[]
): TableGroup[] | null {
  if (!groups.some((group) => groupContainsTable(group, tableName))) return null;
  return groups.map((group) => {
    if (group.rule.type !== 'exact' || !group.rule.tableNames.includes(tableName)) return group;
    return { ...group, rule: { ...group.rule, tableNames: transform(group.rule.tableNames) } };
  });
}

interface TableGroupState {
  groupsByScope: Record<string, TableGroup[]>;
  createGroup: (scope: string, name: string, rule: TableGroupRule) => string;
  renameGroup: (scope: string, groupId: string, name: string) => void;
  deleteGroup: (scope: string, groupId: string) => void;
  updateGroupRule: (scope: string, groupId: string, rule: TableGroupRule) => void;
  addTableToGroup: (scope: string, groupId: string, tableName: string) => void;
  removeTableFromGroup: (scope: string, groupId: string, tableName: string) => void;
  removeTableFromGroups: (scope: string, tableName: string) => void;
  removeGroupsForCatalog: (connectionId: string, catalog: string) => void;
  removeGroupsForConnection: (connectionId: string) => void;
  renameTableInGroups: (scope: string, oldName: string, newName: string) => void;
}

export const useTableGroupStore = create<TableGroupState>()(
  persist(
    (set) => ({
      groupsByScope: {},

      createGroup: (scope, name, rule) => {
        const id = crypto.randomUUID();
        set((state) => ({
          groupsByScope: {
            ...state.groupsByScope,
            [scope]: [...(state.groupsByScope[scope] ?? []), { id, name, rule }],
          },
        }));
        return id;
      },

      renameGroup: (scope, groupId, name) =>
        set((state) => ({
          groupsByScope: {
            ...state.groupsByScope,
            [scope]: (state.groupsByScope[scope] ?? []).map((g) =>
              g.id === groupId ? { ...g, name } : g
            ),
          },
        })),

      deleteGroup: (scope, groupId) =>
        set((state) => ({
          groupsByScope: {
            ...state.groupsByScope,
            [scope]: (state.groupsByScope[scope] ?? []).filter((g) => g.id !== groupId),
          },
        })),

      updateGroupRule: (scope, groupId, rule) =>
        set((state) => ({
          groupsByScope: {
            ...state.groupsByScope,
            [scope]: (state.groupsByScope[scope] ?? []).map((g) =>
              g.id === groupId ? { ...g, rule } : g
            ),
          },
        })),

      addTableToGroup: (scope, groupId, tableName) =>
        set((state) => {
          const groups = state.groupsByScope[scope] ?? [];
          // Enforce single-parent: remove the table from every other manual group first.
          const next = groups
            .map((g) => {
              if (g.rule.type !== 'exact') return g;
              return {
                ...g,
                rule: { ...g.rule, tableNames: g.rule.tableNames.filter((n) => n !== tableName) },
              };
            })
            .map((g) => {
              if (g.id !== groupId || g.rule.type !== 'exact') return g;
              const tableNames = g.rule.tableNames.includes(tableName)
                ? g.rule.tableNames
                : [...g.rule.tableNames, tableName];
              return { ...g, rule: { ...g.rule, tableNames } };
            });
          return { groupsByScope: { ...state.groupsByScope, [scope]: next } };
        }),

      removeTableFromGroup: (scope, groupId, tableName) =>
        set((state) => {
          const groups = state.groupsByScope[scope] ?? [];
          const next = groups.map((g) => {
            if (g.id !== groupId || g.rule.type !== 'exact') return g;
            return {
              ...g,
              rule: { ...g.rule, tableNames: g.rule.tableNames.filter((n) => n !== tableName) },
            };
          });
          return { groupsByScope: { ...state.groupsByScope, [scope]: next } };
        }),

      removeTableFromGroups: (scope, tableName) =>
        set((state) => {
          const next = updateManualTableNames(
            state.groupsByScope[scope] ?? [],
            tableName,
            (tableNames) => tableNames.filter((name) => name !== tableName),
          );
          return next ? { groupsByScope: { ...state.groupsByScope, [scope]: next } } : state;
        }),

      // Scope keys are `${connectionId}|${catalog}|${schema}`. Dropping a
      // database or connection must drop its definitions too, otherwise a
      // re-created database with the same name revives stale groups.
      removeGroupsForCatalog: (connectionId, catalog) =>
        set((state) => {
          const next = withoutScopes(
            state.groupsByScope,
            (scope) => scope.startsWith(`${connectionId}|${catalog}|`),
          );
          return next ? { groupsByScope: next } : state;
        }),

      removeGroupsForConnection: (connectionId) =>
        set((state) => {
          const next = withoutScopes(
            state.groupsByScope,
            (scope) => scope.startsWith(`${connectionId}|`),
          );
          return next ? { groupsByScope: next } : state;
        }),

      renameTableInGroups: (scope, oldName, newName) =>
        set((state) => {
          const next = updateManualTableNames(
            state.groupsByScope[scope] ?? [],
            oldName,
            (tableNames) => [
              ...new Set(tableNames.map((name) => (name === oldName ? newName : name))),
            ],
          );
          return next ? { groupsByScope: { ...state.groupsByScope, [scope]: next } } : state;
        }),
    }),
    {
      name: STORAGE_KEYS.TABLE_GROUPS,
      partialize: (state) => ({ groupsByScope: state.groupsByScope }),
    }
  )
);
