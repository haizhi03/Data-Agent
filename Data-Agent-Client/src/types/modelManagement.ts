export type ApiStyle = 'OPENAI_CHAT_COMPLETIONS' | 'OPENAI_RESPONSES' | 'ANTHROPIC_MESSAGES' | 'DASHSCOPE_CHAT';
export type ThinkingMode = 'DISABLED' | 'ENABLED' | 'ADAPTIVE';
export type ModelPurpose = 'MAIN_CHAT' | 'EXPLORER' | 'PLANNER' | 'COMPRESSION' | 'MEMORY_WRITER';
export interface ModelManagementStatus { enabled: boolean; persistent: boolean; canManage: boolean; credentialStorageReady: boolean }
export interface ConnectionTestInput {
  connectionId?: string; expectedVersion?: number; provider: string; apiStyle: ApiStyle;
  endpoint: string; compatibilityProfile: string; modelId: string; apiKey?: string;
}
export interface ConnectionTestResult { success: boolean; code: string; elapsedMs: number }
export interface ModelConnection {
  id: string; code: string; name: string; provider: string; apiStyle: ApiStyle; endpoint: string; requestUrl: string;
  compatibilityProfile: string; authType: string; hasCredential: boolean; credentialKind: string | null;
  credentialVersion: number; version: number; enabled: boolean;
}
export interface ModelOptions { thinkingMode: ThinkingMode; thinkingBudget?: number | null; temperature?: number | null; maxOutputTokens?: number | null; reasoningEffort?: string | null }
export interface ModelSetupInput {
  modelConfigId?: string; modelExpectedVersion?: number; connectionId?: string; connectionExpectedVersion?: number;
  name: string; provider: string; apiStyle: ApiStyle; endpoint: string; compatibilityProfile: string; apiKey?: string;
  modelId: string; capabilityProfile: string; defaultOptions: ModelOptions; maxContextTokens: number | null; memoryThreshold: number | null;
  chatVisible: boolean; enabled: boolean; makeDefault: boolean; bindingVersions?: Partial<Record<ModelPurpose, number>>;
}
export interface ModelSetupResult { connection: ModelConnection; model: ManagedModel; bindings: PurposeBinding[] }
export interface ManagedModel {
  id: string; code: string; connectionId: string; name: string; modelId: string; capabilityProfile: string; defaultOptions: ModelOptions;
  maxContextTokens: number; memoryThreshold: number; chatVisible: boolean; version: number; enabled: boolean; available: boolean;
  tokenLimits?: { maxContextTokens: number | null; maxOutputTokens: number | null } | null;
  taskPreset?: boolean;
}
export interface PurposeBinding { purpose: ModelPurpose; modelConfigId: string | null; version: number; thinkingMode?: ThinkingMode | null; thinkingModes?: ThinkingMode[] }
export interface PresetBindingInput { expectedVersion: number; connectionId: string; connectionExpectedVersion: number; modelId: string; thinkingMode?: ThinkingMode }
export interface ModelProfile { apiStyle: ApiStyle; compatibilityProfile: string; capabilityProfile: string; thinkingModes: ThinkingMode[]; resource: string }
export interface ModelSpecification {
  modelId: string; aliases: string[]; contextTokens: number; maxOutputTokens: number; maxThinkingOutputTokens: number;
  sourceUrl: string; verifiedAt: string;
}
export type ConnectionInput = Omit<ModelConnection, 'id' | 'version' | 'credentialVersion' | 'credentialKind' | 'authType' | 'hasCredential' | 'requestUrl'> & { expectedVersion?: number; apiKey?: string };
export type ModelInput = Omit<ManagedModel, 'id' | 'version' | 'available' | 'tokenLimits' | 'taskPreset'> & { expectedVersion?: number };
export interface ModelTest {
  id: string; modelConfigId: string; configVersion: number; connectionVersion: number; credentialVersion: number;
  capabilityProfile: string; compatibilityProfile: string; sdkVersion: string; requestedLevel: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED'; result: string; createdAt: string; finishedAt: string | null; current: boolean;
}
