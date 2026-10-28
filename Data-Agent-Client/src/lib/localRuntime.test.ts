import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import { initializeRuntime, failLocalRuntime, localRequestTransport, localFetch } from './localRuntime';
import { useRuntimeStore } from '../store/runtimeStore';
import http from './http';
import { ensureValidAccessToken } from './authToken';

vi.mock('./authToken', () => ({ ensureValidAccessToken: vi.fn(), clearAuthAndOpenLogin: vi.fn(),
  applyRefreshedTokens: vi.fn(), resolveRefreshAccessTokenResult: vi.fn() }));
vi.mock('../store/authStore', () => ({ useAuthStore: { getState: () => ({ workspaceType: 'ORGANIZATION', workspaceOrgId: 42 }) } }));
const bootstrap = { contractVersion: 1 as const, apiBaseURL: 'http://127.0.0.1:12345/api',
  runtimeInstanceId: '00000000-0000-4000-8000-000000000001', transportToken: 't'.repeat(43) };
const pending = { state: 'NOT_IMPLEMENTED', reasonCode: 'FEATURE_NOT_IMPLEMENTED' };
const capabilities = { ...bootstrap, mode: 'LOCAL', accountLoginEnabled: false, organizationEnabled: false,
  chatHistoryPersistenceEnabled: false, features: { databaseWorkbench: { state: 'READY', reasonCode: null },
    aiAssistant: pending, knowledgeMemory: pending, skills: pending } };

beforeEach(() => {
  failLocalRuntime(); useRuntimeStore.setState({ mode: 'SERVER', status: 'READY', capabilities: null, error: null });
  vi.clearAllMocks();
  vi.stubGlobal('window', { dataAgentHost: { getBootstrap: async () => bootstrap } });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ code: 200, data: capabilities }) }));
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe('trusted local startup', () => {
  it('connects directly to the fixed backend when no host is present', async () => {
    vi.stubEnv('VITE_RUNTIME_MODE', undefined); vi.stubGlobal('window', {});
    await initializeRuntime();
    expect(useRuntimeStore.getState().status).toBe('READY');
    expect(fetch).toHaveBeenCalledWith('http://127.0.0.1:8081/api/runtime/capabilities', expect.objectContaining({
      headers: undefined, cache: 'no-store' }));
    expect(localRequestTransport().baseURL).toBe('http://127.0.0.1:8081/api');
    expect(localRequestTransport().authorization).toBeNull();
  });
  it('rejects the retired server build mode instead of using account login', async () => {
    vi.stubEnv('VITE_RUNTIME_MODE', 'server');
    await initializeRuntime(); expect(useRuntimeStore.getState().status).toBe('FAILED'); expect(fetch).not.toHaveBeenCalled();
  });
  it('keeps credentials out of public runtime state and uses the authenticated capabilities response', async () => {
    await initializeRuntime();
    expect(useRuntimeStore.getState().status).toBe('READY');
    expect(fetch).toHaveBeenCalledWith(`${bootstrap.apiBaseURL}/runtime/capabilities`, expect.objectContaining({
      headers: { Authorization: `Bearer ${bootstrap.transportToken}` }, cache: 'no-store' }));
    expect(JSON.stringify(useRuntimeStore.getState())).not.toContain(bootstrap.transportToken);
  });
  it('rejects a non-loopback host before sending a credential', async () => {
    vi.stubGlobal('window', { dataAgentHost: { getBootstrap: async () => ({ ...bootstrap, apiBaseURL: 'https://example.invalid/api' }) } });
    await initializeRuntime(); expect(fetch).not.toHaveBeenCalled(); expect(useRuntimeStore.getState().status).toBe('FAILED');
  });
  it('rejects capabilities belonging to a different Java startup', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: true, json: async () => ({ code: 200, data: { ...capabilities, runtimeInstanceId: 'other' } }) } as Response);
    await initializeRuntime(); expect(useRuntimeStore.getState().status).toBe('FAILED');
  });
  it('uses the memory credential and personal scope without consulting old login or organization state', async () => {
    await initializeRuntime();
    let seen: any;
    await http.get('/connections', { adapter: async config => {
      seen = config; return { data: { code: 200, data: [] }, status: 200, statusText: 'OK', headers: {}, config };
    } });
    expect(seen.baseURL).toBe(bootstrap.apiBaseURL);
    expect(seen.headers.Authorization).toBe(`Bearer ${bootstrap.transportToken}`);
    expect(seen.headers.get('X-Workspace-Type')).toBe('PERSONAL');
    expect(seen.headers.get('X-Org-Id')).toBeUndefined(); expect(ensureValidAccessToken).not.toHaveBeenCalled();
    expect(() => localRequestTransport('https://example.invalid/api')).toThrow();
  });
  it('fails closed on 401 and removes the credential from errors instead of refreshing an account token', async () => {
    await initializeRuntime();
    const error = await http.get('/connections', { adapter: async config => {
      throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, {
        data: { code: 401 }, status: 401, statusText: 'Unauthorized', headers: {}, config,
      });
    } }).catch(error => error);
    expect(useRuntimeStore.getState().status).toBe('FAILED');
    expect(JSON.stringify(error.toJSON())).not.toContain(bootstrap.transportToken);
    expect(ensureValidAccessToken).not.toHaveBeenCalled();
  });
  it('uses local fetch credentials and the runtime id without inherited organization hints', async () => {
    await initializeRuntime(); vi.mocked(fetch).mockClear();
    await localFetch('/local/chat/stream', { headers: { 'X-Org-Id': '42', Authorization: 'old-account' } });
    const [url, options] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe(`${bootstrap.apiBaseURL}/local/chat/stream`);
    const headers = new Headers(options?.headers);
    expect(headers.get('X-Org-Id')).toBeNull(); expect(headers.get('X-Workspace-Type')).toBe('PERSONAL');
    expect(headers.get('X-Local-Runtime-Id')).toBe(bootstrap.runtimeInstanceId);
    expect(headers.get('Authorization')).toBe(`Bearer ${bootstrap.transportToken}`);
  });
  it('redacts draft model keys from rejected request errors', async () => {
    await initializeRuntime();
    const error = await http.post('/ai/model-setups', { apiKey: 'draft-secret' }, { adapter: async config => {
      throw new AxiosError('Bad request', 'ERR_BAD_REQUEST', config, undefined, {
        data: { code: 400 }, status: 400, statusText: 'Bad request', headers: {}, config,
      });
    } }).catch(error => error);
    expect(JSON.stringify(error.toJSON())).not.toContain('draft-secret');
    expect(JSON.stringify(error.toJSON())).not.toContain(bootstrap.transportToken);
  });
});
