import http from '../lib/http';
import { useAuthStore } from '../store/authStore';
import { X_ORG_ID, X_WORKSPACE_TYPE } from '../constants/workspaceHeaders';
import type { ConnectionInput, ConnectionTestInput, ConnectionTestResult, ManagedModel, ModelConnection, ModelInput, ModelManagementStatus, ModelProfile, ModelPurpose, ModelSetupInput, ModelSetupResult, ModelSpecification, ModelTest, PresetBindingInput, PurposeBinding, ThinkingMode } from '../types/modelManagement';

/** Freeze request ownership when the screen is created, including during token refresh. */
export function createModelManagementService() {
  const { user, workspaceType, workspaceOrgId } = useAuthStore.getState();
  const headers: Record<string, string> = { [X_WORKSPACE_TYPE]: workspaceType };
  if (workspaceType === 'ORGANIZATION' && workspaceOrgId != null) headers[X_ORG_ID] = String(workspaceOrgId);
  const principal = user?.id;
  const options = () => {
    if (useAuthStore.getState().user?.id !== principal) throw new Error('MODEL_MANAGEMENT_FORBIDDEN');
    return { headers };
  };
  return {
    status: async () => (await http.get<ModelManagementStatus>('/ai/model-management', options())).data,
    profiles: async () => (await http.get<ModelProfile[]>('/ai/model-profiles', options())).data,
    specifications: async () => (await http.get<ModelSpecification[]>('/ai/model-specifications', options())).data,
    connections: async () => (await http.get<ModelConnection[]>('/ai/model-connections', options())).data,
    models: async () => (await http.get<ManagedModel[]>('/ai/model-configs', options())).data,
    bindings: async () => (await http.get<PurposeBinding[]>('/ai/model-bindings', options())).data,
    saveSetup: async (body: ModelSetupInput) => (await http.post<ModelSetupResult>('/ai/model-setups', body, options())).data,
    saveConnection: async (body: ConnectionInput, id?: string) => id
      ? (await http.patch<ModelConnection>(`/ai/model-connections/${id}`, body, options())).data
      : (await http.post<ModelConnection>('/ai/model-connections', body, options())).data,
    testConnection: async (body: ConnectionTestInput, signal?: AbortSignal) =>
      (await http.post<ConnectionTestResult>('/ai/model-connections/test', body, { ...options(), timeout: 30000, signal })).data,
    credential: async (id: string, expectedVersion: number, action: 'REPLACE' | 'UNLINK', apiKey?: string) =>
      (await http.post<ModelConnection>(`/ai/model-connections/${id}/credential`, { expectedVersion, action, ...(apiKey === undefined ? {} : { apiKey }) }, options())).data,
    disableConnection: async (id: string, expectedVersion: number) =>
      (await http.post<ModelConnection>(`/ai/model-connections/${id}/disable`, { expectedVersion }, options())).data,
    saveModel: async (body: ModelInput, id?: string) => id
      ? (await http.patch<ManagedModel>(`/ai/model-configs/${id}`, body, options())).data
      : (await http.post<ManagedModel>('/ai/model-configs', body, options())).data,
    disableModel: async (id: string, expectedVersion: number) =>
      (await http.post<ManagedModel>(`/ai/model-configs/${id}/disable`, { expectedVersion }, options())).data,
    bind: async (purpose: ModelPurpose, modelConfigId: string, expectedVersion: number, thinkingMode?: ThinkingMode) =>
      (await http.put<PurposeBinding>(`/ai/model-bindings/${purpose}`, { expectedVersion, modelConfigId, ...(thinkingMode === undefined ? {} : { thinkingMode }) }, options())).data,
    bindPreset: async (purpose: ModelPurpose, body: PresetBindingInput) =>
      (await http.put<PurposeBinding>(`/ai/model-bindings/${purpose}/preset`, body, options())).data,
    test: async (id: string, level: string) => (await http.post<ModelTest>(`/ai/model-configs/${id}/test`, { level }, options())).data,
    testResult: async (id: string) => (await http.get<ModelTest>(`/ai/model-tests/${id}`, options())).data,
    tests: async (id: string) => (await http.get<ModelTest[]>(`/ai/model-configs/${id}/tests`, options())).data,
  };
}

const codes = ['MODEL_CONFIG_CONFLICT', 'MODEL_CONFIG_INVALID', 'MODEL_TOKEN_LIMITS_INVALID', 'MODEL_CONFIG_NOT_FOUND', 'MODEL_MANAGEMENT_FORBIDDEN', 'MODEL_MANAGEMENT_UNAVAILABLE', 'MODEL_TEST_RATE_LIMITED', 'MODEL_TEST_NOT_FOUND', 'MODEL_TEST_CREDENTIAL_REQUIRED'] as const;
export function modelManagementError(error: unknown): typeof codes[number] | 'UNKNOWN' {
  const value = error as { response?: { data?: { message?: unknown } }; message?: unknown };
  const code = value?.response?.data?.message ?? value?.message;
  return codes.find((known) => known === code) ?? 'UNKNOWN';
}
