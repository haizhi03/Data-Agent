import type { ModelOption } from '../types/ai';

/** Availability and defaults come exclusively from the current workspace's server response. */
export const FALLBACK_MODELS: ModelOption[] = [];
export const DEFAULT_MODEL = '';

export function defaultModelSelection(options: ModelOption[]): string {
  return (options.find((m) => m.defaultModel) ?? options[0])?.modelName ?? '';
}

export function modelRequest(selection: string, options: ModelOption[]): { modelConfigId: string } | { model: string } {
  const option = options.find((m) => m.modelName === selection);
  if (!option) return { model: '__unavailable__' };
  return option.modelConfigId ? { modelConfigId: option.modelConfigId } : { model: option.modelName };
}
