import http from '../lib/http';
import type { ModelOption } from '../types/ai';

function normalizePositiveNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
}

function normalizeModelOption(input: Partial<ModelOption>): ModelOption {
  const modelName = input.modelConfigId ?? input.modelName ?? '';

  return {
    modelName,
    modelConfigId: input.modelConfigId,
    modelId: input.modelId,
    name: input.name,
    provider: input.provider,
    apiStyle: input.apiStyle,
    compatibilityProfile: input.compatibilityProfile,
    defaultModel: input.defaultModel === true,
    supportThinking: typeof input.supportThinking === 'boolean'
      ? input.supportThinking
      : false,
    thinkingMode: input.thinkingMode,
    thinkingModes: input.thinkingModes,
    memoryThreshold: normalizePositiveNumber(input.memoryThreshold),
    maxContextTokens: normalizePositiveNumber(input.maxContextTokens),
  };
}

export const aiService = {
  /**
   * Get available chat models (e.g. for model selector).
   * Failure is an empty, non-callable state. No cross-workspace availability cache.
   */
  getModels: async (_forceRefresh = false): Promise<ModelOption[]> => {
    try {
      const response = await http.get<ModelOption[]>('/ai/models');
      const list = Array.isArray(response.data) ? response.data : [];
      return list.map((m) => normalizeModelOption(m)).filter((m) => m.modelName.length > 0);
    } catch {
      return [];
    }
  },
};
