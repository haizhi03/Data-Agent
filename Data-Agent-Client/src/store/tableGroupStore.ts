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

interface TableGroupState {
  groupsByScope: Record<string, TableGroup[]>;
  createGroup: (scope: string, name: string, rule: TableGroupRule) => string;
  renameGroup: (scope: string, groupId: string, name: string) => void;
  deleteGroup: (scope: string, groupId: string) => void;
  updateGroupRule: (scope: string, groupId: string, rule: TableGroupRule) => void;
  addTableToGroup: (scope: string, groupId: string, tableName: string) => void;
  removeTableFromGroup: (scope: string, groupId: string, tableName: string) => void;
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
    }),
    {
      name: STORAGE_KEYS.TABLE_GROUPS,
      partialize: (state) => ({ groupsByScope: state.groupsByScope }),
    }
  )
);
