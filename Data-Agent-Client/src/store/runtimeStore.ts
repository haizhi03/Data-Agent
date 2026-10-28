import { create } from 'zustand';

export interface RuntimeCapabilities {
  contractVersion: 1;
  mode: 'LOCAL';
  runtimeInstanceId: string;
  accountLoginEnabled: false;
  organizationEnabled: false;
  chatHistoryPersistenceEnabled: false;
  features: Record<'databaseWorkbench' | 'aiAssistant' | 'knowledgeMemory' | 'skills', {
    state: 'READY' | 'NOT_IMPLEMENTED' | 'UNCONFIGURED' | 'UNAVAILABLE'; reasonCode: string | null;
  }>;
}
interface RuntimeState {
  mode: 'SERVER' | 'LOCAL';
  status: 'STARTING' | 'READY' | 'FAILED';
  capabilities: RuntimeCapabilities | null;
  error: string | null;
}
// Deliberately not persisted. Authentication material never belongs in this store.
export const useRuntimeStore = create<RuntimeState>(() => ({ mode: 'LOCAL', status: 'STARTING', capabilities: null, error: null }));
export const localWorkbenchReady = (s: RuntimeState) => s.mode === 'LOCAL' && s.status === 'READY'
  && s.capabilities?.features.databaseWorkbench.state === 'READY';
