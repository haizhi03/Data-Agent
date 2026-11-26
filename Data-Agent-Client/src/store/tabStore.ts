import { create } from 'zustand';
import type {
  ConsoleTabMetadata,
  PlanTabMetadata,
  SubAgentConsoleTabMetadata,
  TableTabMetadata,
} from '../types/tab';

export type WorkspaceTabMetadata =
  | ConsoleTabMetadata
  | TableTabMetadata
  | PlanTabMetadata
  | SubAgentConsoleTabMetadata;

export type WorkspaceTabMetadataUpdate = Partial<ConsoleTabMetadata & TableTabMetadata>;

export interface Tab {
  id: string;
  name: string;
  type: 'file' | 'table' | 'plan' | 'subagent-console' | 'er';
  icon?: string;
  content?: string;
  contentVersion?: number;
  selection?: { start: number; end: number };
  active: boolean;
  metadata?: WorkspaceTabMetadata;
}

interface TabState {
  tabs: Tab[];
  activeTabId: string | null;

  // Actions
  openTab: (tab: Omit<Tab, 'active'>) => void;
  closeTab: (id: string) => void;
  closeTabsToLeft: (id: string) => void;
  closeTabsToRight: (id: string) => void;
  closeOtherTabs: (id: string) => void;
  closeAllTabs: () => void;
  switchTab: (id: string) => void;
  updateTabContent: (id: string, content: string) => void;
  updateTabSelection: (id: string, content: string, start: number, end: number) => void;
  updateTabMetadata: (id: string, metadata: WorkspaceTabMetadataUpdate) => void;
  updatePlanPayload: (id: string, payload: PlanTabMetadata['planPayload']) => void;
  updateSubAgentConsole: (id: string, metadata: SubAgentConsoleTabMetadata) => void;
  reorderTabs: (sourceId: string, destinationId: string) => void;
}

function resolveActiveId(tabs: Tab[], removedId: string, currentActiveId: string | null): string | null {
  if (currentActiveId !== removedId) return currentActiveId;
  const idx = tabs.findIndex((t) => t.id === removedId);
  const remaining = tabs.filter((t) => t.id !== removedId);
  if (remaining.length === 0) return null;
  // prefer the tab to the right, fall back to left
  const next = remaining[idx] ?? remaining[idx - 1];
  return next.id;
}

export const useTabStore = create<TabState>((set) => ({
  tabs: [],
  activeTabId: null,

  openTab: (newTab) =>
    set((state) => {
      const existingTab = state.tabs.find((t) => t.id === newTab.id);
      if (existingTab) {
        return {
          tabs: state.tabs.map((t) => ({ ...t, active: t.id === newTab.id })),
          activeTabId: newTab.id,
        };
      }
      return {
        tabs: [...state.tabs.map((t) => ({ ...t, active: false })), { ...newTab, active: true }],
        activeTabId: newTab.id,
      };
    }),

  closeTab: (id) =>
    set((state) => {
      const newActiveId = resolveActiveId(state.tabs, id, state.activeTabId);
      const newTabs = state.tabs.filter((t) => t.id !== id);
      return {
        tabs: newTabs.map((t) => ({ ...t, active: t.id === newActiveId })),
        activeTabId: newActiveId,
      };
    }),

  closeTabsToLeft: (id) =>
    set((state) => {
      const idx = state.tabs.findIndex((t) => t.id === id);
      if (idx <= 0) return state;
      const removedIds = new Set(state.tabs.slice(0, idx).map((t) => t.id));
      const newTabs = state.tabs.filter((t) => !removedIds.has(t.id));
      const newActiveId = removedIds.has(state.activeTabId ?? '') ? id : state.activeTabId;
      return {
        tabs: newTabs.map((t) => ({ ...t, active: t.id === newActiveId })),
        activeTabId: newActiveId,
      };
    }),

  closeTabsToRight: (id) =>
    set((state) => {
      const idx = state.tabs.findIndex((t) => t.id === id);
      if (idx === -1 || idx === state.tabs.length - 1) return state;
      const removedIds = new Set(state.tabs.slice(idx + 1).map((t) => t.id));
      const newTabs = state.tabs.filter((t) => !removedIds.has(t.id));
      const newActiveId = removedIds.has(state.activeTabId ?? '') ? id : state.activeTabId;
      return {
        tabs: newTabs.map((t) => ({ ...t, active: t.id === newActiveId })),
        activeTabId: newActiveId,
      };
    }),

  closeOtherTabs: (id) =>
    set((state) => {
      const newTabs = state.tabs.filter((t) => t.id === id);
      return {
        tabs: newTabs.map((t) => ({ ...t, active: true })),
        activeTabId: id,
      };
    }),

  closeAllTabs: () =>
    set({ tabs: [], activeTabId: null }),

  switchTab: (id) =>
    set((state) => ({
      tabs: state.tabs.map((t) => ({ ...t, active: t.id === id })),
      activeTabId: id,
    })),

  updateTabContent: (id, content) =>
    set((state) => ({
      tabs: state.tabs.map((t) => (t.id === id && t.content !== content
        ? { ...t, content, contentVersion: (t.contentVersion ?? 0) + 1, selection: undefined } : t)),
    })),

  updateTabSelection: (id, content, start, end) =>
    set((state) => {
      const tab = state.tabs.find(item => item.id === id);
      if (state.activeTabId !== id || !tab || (tab.content ?? '') !== content
          || !Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start || end > content.length) return state;
      const selection = start < end ? { start, end } : undefined;
      if (tab.selection?.start === selection?.start && tab.selection?.end === selection?.end) return state;
      return { tabs: state.tabs.map(item => item.id === id ? { ...item, selection } : item) };
    }),

  updateTabMetadata: (id, metadata) =>
    set((state) => ({
      tabs: state.tabs.map((t) =>
        t.id === id ? { ...t, metadata: { ...t.metadata, ...metadata } as WorkspaceTabMetadata } : t
      ),
    })),

  updatePlanPayload: (id, payload) =>
    set((state) => ({
      tabs: state.tabs.map((t) =>
        t.id === id && t.type === 'plan'
          ? { ...t, metadata: { planPayload: payload } as PlanTabMetadata }
          : t
      ),
    })),

  updateSubAgentConsole: (id, metadata) =>
    set((state) => ({
      tabs: state.tabs.map((t) =>
        t.id === id && t.type === 'subagent-console'
          ? { ...t, metadata }
          : t
      ),
    })),

  reorderTabs: (sourceId, destinationId) =>
    set((state) => {
      const tabs = [...state.tabs];
      const fromIndex = tabs.findIndex((t) => t.id === sourceId);
      const toIndex = tabs.findIndex((t) => t.id === destinationId);
      if (fromIndex === -1 || toIndex === -1 || fromIndex === toIndex) return state;
      const [moved] = tabs.splice(fromIndex, 1);
      tabs.splice(toIndex, 0, moved);
      return { tabs };
    }),
}));
