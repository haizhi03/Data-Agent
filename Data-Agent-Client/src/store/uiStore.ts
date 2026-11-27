import { create } from 'zustand';

/** Request to open the AI sidebar with a prefilled composer (e.g. from a failed SQL). */
export interface AiComposeRequest {
  id: number;
  text: string;
}

interface UiState {
  isSettingsModalOpen: boolean;
  setSettingsModalOpen: (open: boolean) => void;
  aiComposeRequest: AiComposeRequest | null;
  requestAiCompose: (text: string) => void;
}

let aiComposeSequence = 0;

export const useUiStore = create<UiState>((set) => ({
  isSettingsModalOpen: false,
  setSettingsModalOpen: (open) => set({ isSettingsModalOpen: open }),
  aiComposeRequest: null,
  requestAiCompose: (text) =>
    set({ aiComposeRequest: { id: (aiComposeSequence += 1), text } }),
}));
