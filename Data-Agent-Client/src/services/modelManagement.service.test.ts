import { beforeEach, describe, expect, it, vi } from 'vitest';
import http from '../lib/http';
import { createModelManagementService, modelManagementError } from './modelManagement.service';
import { setupFromModel } from '../lib/modelSetup';

const { scope } = vi.hoisted(() => ({ scope: { user: { id: 1 }, workspaceType: 'ORGANIZATION', workspaceOrgId: 90 as number | null } }));
vi.mock('../lib/http', () => ({ default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), put: vi.fn() } }));
vi.mock('../store/authStore', () => ({ useAuthStore: { getState: () => scope } }));
describe('model management request ownership and credentials', () => {
  beforeEach(() => { vi.resetAllMocks(); scope.user.id = 1; scope.workspaceType = 'ORGANIZATION'; scope.workspaceOrgId = 90; vi.mocked(http.get).mockResolvedValue({ data: [] }); vi.mocked(http.post).mockResolvedValue({ data: {} }); });
  it('pins the original workspace even if the current selection changes', async () => {
    const api = createModelManagementService(); scope.workspaceOrgId = 91;
    await api.connections();
    expect(http.get).toHaveBeenCalledWith('/ai/model-connections', { headers: { 'X-Workspace-Type': 'ORGANIZATION', 'X-Org-Id': '90' } });
    await api.specifications();
    expect(http.get).toHaveBeenLastCalledWith('/ai/model-specifications', { headers: { 'X-Workspace-Type': 'ORGANIZATION', 'X-Org-Id': '90' } });
  });
  it('prevents a queued request being sent under another principal', async () => {
    const api = createModelManagementService(); scope.user.id = 2;
    await expect(api.connections()).rejects.toThrow('MODEL_MANAGEMENT_FORBIDDEN'); expect(http.get).not.toHaveBeenCalled();
  });
  it('uses explicit credential actions and optimistic versions without placeholder keys', async () => {
    const api = createModelManagementService(); await api.credential('9007199254740993', 4, 'UNLINK');
    expect(http.post).toHaveBeenCalledWith('/ai/model-connections/9007199254740993/credential', { expectedVersion: 4, action: 'UNLINK' }, expect.anything());
    await api.credential('3', 7, 'REPLACE', 'fixture-key');
    expect(http.post).toHaveBeenLastCalledWith('/ai/model-connections/3/credential', { expectedVersion: 7, action: 'REPLACE', apiKey: 'fixture-key' }, expect.anything());
  });
  it('only classifies known stable errors and never displays arbitrary server bodies', () => {
    expect(modelManagementError({ response: { data: { message: 'MODEL_CONFIG_CONFLICT' } } })).toBe('MODEL_CONFIG_CONFLICT');
    expect(modelManagementError({ response: { data: { message: 'MODEL_TEST_NOT_FOUND' } } })).toBe('MODEL_TEST_NOT_FOUND');
    expect(modelManagementError({ response: { data: { message: 'MODEL_TOKEN_LIMITS_INVALID' } } })).toBe('MODEL_TOKEN_LIMITS_INVALID');
    expect(modelManagementError({ response: { data: { message: 'upstream contains fixture-key' } } })).toBe('UNKNOWN');
    expect(modelManagementError(null)).toBe('UNKNOWN');
  });
  it('tests drafts in the captured workspace with a deadline and cancellation signal', async () => {
    const api = createModelManagementService();scope.workspaceOrgId = 91;
    const controller = new AbortController();
    const draft = { provider: 'QWEN', apiStyle: 'OPENAI_CHAT_COMPLETIONS' as const, compatibilityProfile: 'qwen-chat-v1',
      endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelId: 'fixture-model', apiKey: 'fixture-draft-key' };
    await api.testConnection(draft, controller.signal);
    expect(http.post).toHaveBeenCalledWith('/ai/model-connections/test', draft, {
      headers: { 'X-Workspace-Type': 'ORGANIZATION', 'X-Org-Id': '90' }, timeout: 30000, signal: controller.signal,
    });
    expect(http.patch).not.toHaveBeenCalled();
  });
  it('saves model, connection and replacement key with one request under the captured workspace', async () => {
    const api = createModelManagementService();scope.workspaceOrgId = 91;
    const body = { ...setupFromModel([]), modelId: 'fixture-model', apiKey: 'fixture-key', connectionId: 'connection-id', connectionExpectedVersion: 2,
      modelConfigId: 'model-id', modelExpectedVersion: 3 };
    await api.saveSetup(body);
    expect(http.post).toHaveBeenCalledTimes(1);expect(http.post).toHaveBeenCalledWith('/ai/model-setups', body,
      { headers: { 'X-Workspace-Type': 'ORGANIZATION', 'X-Org-Id': '90' } });expect(http.patch).not.toHaveBeenCalled();
    vi.mocked(http.post).mockClear();scope.user.id = 2;
    await expect(api.saveSetup(body)).rejects.toThrow('MODEL_MANAGEMENT_FORBIDDEN');expect(http.post).not.toHaveBeenCalled();
  });
  it('tests a stored credential by reference and blocks a changed principal', async () => {
    const api = createModelManagementService();
    const input = { connectionId: 'fc-P-1-fixture', expectedVersion: 4, provider: 'OPENAI', apiStyle: 'OPENAI_RESPONSES' as const,
      compatibilityProfile: 'openai-responses-v1', endpoint: 'https://example.com/v1', modelId: 'fixture-model' };
    await api.testConnection(input);
    expect(http.post).toHaveBeenCalledWith('/ai/model-connections/test', input, expect.anything());
    expect(vi.mocked(http.post).mock.calls[0][1]).not.toHaveProperty('apiKey');
    vi.mocked(http.post).mockClear();scope.user.id = 2;
    await expect(api.testConnection(input)).rejects.toThrow('MODEL_MANAGEMENT_FORBIDDEN');expect(http.post).not.toHaveBeenCalled();
  });
  it('binds a provider preset with both optimistic versions under the captured workspace', async () => {
    vi.mocked(http.put).mockResolvedValue({ data: {} });
    const api = createModelManagementService();scope.workspaceOrgId = 91;
    const body = { connectionId: 'configured-connection', connectionExpectedVersion: 3, modelId: 'qwen3.6-flash', expectedVersion: 2 };
    await api.bindPreset('EXPLORER', body);
    expect(http.put).toHaveBeenCalledWith('/ai/model-bindings/EXPLORER/preset', body,
      { headers: { 'X-Workspace-Type': 'ORGANIZATION', 'X-Org-Id': '90' } });
    expect(body).not.toHaveProperty('apiKey');
    vi.mocked(http.put).mockClear();scope.user.id = 2;
    await expect(api.bindPreset('EXPLORER', body)).rejects.toThrow('MODEL_MANAGEMENT_FORBIDDEN');expect(http.put).not.toHaveBeenCalled();
  });
  it('saves a task thinking override with the binding version without modifying the shared model', async () => {
    vi.mocked(http.put).mockResolvedValue({ data: {} });
    const api = createModelManagementService();scope.workspaceOrgId = 91;
    await api.bind('PLANNER', 'shared-model', 4, 'ENABLED');
    expect(http.put).toHaveBeenLastCalledWith('/ai/model-bindings/PLANNER', { expectedVersion: 4, modelConfigId: 'shared-model', thinkingMode: 'ENABLED' },
      { headers: { 'X-Workspace-Type': 'ORGANIZATION', 'X-Org-Id': '90' } });
    await api.bind('COMPRESSION', 'shared-model', 2, 'DISABLED');
    expect(http.put).toHaveBeenLastCalledWith('/ai/model-bindings/COMPRESSION', { expectedVersion: 2, modelConfigId: 'shared-model', thinkingMode: 'DISABLED' }, expect.anything());
    expect(http.patch).not.toHaveBeenCalled();
    vi.mocked(http.put).mockClear();scope.user.id = 2;
    await expect(api.bind('PLANNER', 'shared-model', 5, 'DISABLED')).rejects.toThrow('MODEL_MANAGEMENT_FORBIDDEN');
    expect(http.put).not.toHaveBeenCalled();
  });
});
