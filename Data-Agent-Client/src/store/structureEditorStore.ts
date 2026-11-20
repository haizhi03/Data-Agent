import { create } from 'zustand';

interface StructureEditorState {
  dirtyIds: Record<string, true>;
  setDirty: (tabId: string, dirty: boolean) => void;
}

export const useStructureEditorStore = create<StructureEditorState>((set) => ({
  dirtyIds: {},
  setDirty: (tabId, dirty) => set((state) => {
    if (dirty === Boolean(state.dirtyIds[tabId])) return state;
    const dirtyIds = { ...state.dirtyIds };
    if (dirty) dirtyIds[tabId] = true;
    else delete dirtyIds[tabId];
    return { dirtyIds };
  }),
}));
