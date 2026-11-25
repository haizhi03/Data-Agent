import { create } from 'zustand';
import type { ErQueryFocusPayload } from '../components/workspace/erDiagramFocus';

interface ErQueryFocusState {
  focus: ErQueryFocusPayload | null;
  draft: string | null;
  attach: (focus: ErQueryFocusPayload, draft: string) => void;
  clear: () => void;
}

export const useErQueryFocusStore = create<ErQueryFocusState>((set) => ({
  focus: null,
  draft: null,
  attach: (focus, draft) => set({ focus, draft }),
  clear: () => set({ focus: null, draft: null }),
}));
