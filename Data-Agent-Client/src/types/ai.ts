import type { ThinkingMode } from './modelManagement';

/** Option for chat model selection (matches backend ModelOptionResponse). */
export interface ModelOption {
  modelName: string;
  modelConfigId?: string;
  /** Upstream model ID for display; requests still use the scoped configuration ID. */
  modelId?: string;
  name?: string;
  provider?: string;
  apiStyle?: string;
  compatibilityProfile?: string;
  defaultModel?: boolean;
  supportThinking: boolean;
  thinkingMode?: ThinkingMode;
  thinkingModes?: ThinkingMode[];
  memoryThreshold: number | null;
  maxContextTokens: number | null;
}
