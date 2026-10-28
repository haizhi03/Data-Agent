import { useRuntimeStore, type RuntimeCapabilities } from '../store/runtimeStore';

export interface LocalBootstrap { contractVersion: 1; apiBaseURL: string; runtimeInstanceId: string; transportToken: string }
declare global { interface Window { dataAgentHost?: { getBootstrap: () => Promise<LocalBootstrap> } } }
let transport: LocalBootstrap | null = null;
let initializing: Promise<void> | null = null;

export function failLocalRuntime() {
  transport = null;
  useRuntimeStore.setState({ mode: 'LOCAL', status: 'FAILED', capabilities: null, error: 'runtime.connection_lost' });
}
export function localRequestTransport(url = '') {
  if (!transport || useRuntimeStore.getState().status !== 'READY') throw new Error('Local runtime is unavailable');
  const target = new URL(url.replace(/^\//, ''), `${transport.apiBaseURL}/`);
  if (target.origin !== new URL(transport.apiBaseURL).origin || !target.pathname.startsWith('/api/')) {
    throw new Error('Local transport cannot be used outside the application API');
  }
  return { baseURL: transport.apiBaseURL, authorization: transport.transportToken ? `Bearer ${transport.transportToken}` : null };
}
export async function localFetch(path: string, init: RequestInit = {}) {
  const local = localRequestTransport(path);
  const headers = new Headers(init.headers);
  if (local.authorization) headers.set('Authorization', local.authorization);
  else headers.delete('Authorization');
  headers.set('X-Workspace-Type', 'PERSONAL');
  headers.delete('X-Org-Id');
  headers.set('X-Local-Runtime-Id', useRuntimeStore.getState().capabilities!.runtimeInstanceId);
  const response = await fetch(`${local.baseURL}/${path.replace(/^\//, '')}`, { ...init, headers, cache: 'no-store' });
  if (response.status === 401) failLocalRuntime();
  return response;
}
export function initializeRuntime(): Promise<void> {
  if (initializing) return initializing;
  initializing = (async () => {
    const host = typeof window !== 'undefined' ? window.dataAgentHost : undefined;
    useRuntimeStore.setState({ mode: 'LOCAL', status: 'STARTING', capabilities: null, error: null });
    try {
      if (import.meta.env.VITE_RUNTIME_MODE && import.meta.env.VITE_RUNTIME_MODE !== 'local') throw new Error('Unsupported runtime mode');
      const bootstrap = host ? await host.getBootstrap() : {
        contractVersion: 1 as const, apiBaseURL: 'http://127.0.0.1:8081/api', runtimeInstanceId: '', transportToken: '',
      };
      const endpoint = new URL(bootstrap.apiBaseURL);
      if (bootstrap.contractVersion !== 1 || !/^https?:$/.test(endpoint.protocol)
        || !['127.0.0.1', '[::1]'].includes(endpoint.hostname) || endpoint.username || endpoint.password
        || endpoint.pathname !== '/api' || endpoint.search || endpoint.hash
        || (host && !/^[A-Za-z0-9_-]{43}$/.test(bootstrap.transportToken))) throw new Error('Invalid local bootstrap');
      const headers = bootstrap.transportToken ? { Authorization: `Bearer ${bootstrap.transportToken}` } : undefined;
      const response = await fetch(`${bootstrap.apiBaseURL}/runtime/capabilities`, {
        headers, cache: 'no-store', signal: AbortSignal.timeout(10000),
      });
      if (!response.ok) throw new Error('Local handshake rejected');
      const envelope = await response.json(); const capabilities = envelope.data as RuntimeCapabilities;
      if (envelope.code !== 200 || capabilities?.contractVersion !== 1 || capabilities.mode !== 'LOCAL'
        || (bootstrap.runtimeInstanceId && capabilities.runtimeInstanceId !== bootstrap.runtimeInstanceId)
        || capabilities.accountLoginEnabled !== false || capabilities.organizationEnabled !== false
        || capabilities.chatHistoryPersistenceEnabled !== false || !capabilities.features?.databaseWorkbench) {
        throw new Error('Invalid capability contract');
      }
      const features = {} as RuntimeCapabilities['features'];
      for (const name of ['databaseWorkbench', 'aiAssistant', 'knowledgeMemory', 'skills'] as const) {
        const feature = capabilities.features[name];
        if (!feature || !['READY', 'NOT_IMPLEMENTED', 'UNCONFIGURED', 'UNAVAILABLE'].includes(feature.state)
          || (feature.state === 'READY' ? feature.reasonCode !== null : typeof feature.reasonCode !== 'string')) {
          throw new Error('Invalid feature contract');
        }
        features[name] = { state: feature.state, reasonCode: feature.reasonCode };
      }
      transport = Object.freeze({ ...bootstrap, runtimeInstanceId: capabilities.runtimeInstanceId });
      useRuntimeStore.setState({ mode: 'LOCAL', status: 'READY', error: null, capabilities: {
        contractVersion: 1, mode: 'LOCAL', runtimeInstanceId: capabilities.runtimeInstanceId,
        accountLoginEnabled: false, organizationEnabled: false, chatHistoryPersistenceEnabled: false, features,
      } });
    } catch {
      failLocalRuntime();
    }
  })().finally(() => { initializing = null; });
  return initializing;
}
