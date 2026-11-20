import { filterModelCandidates } from './modelSpecifications';
/** Fallback for older servers without the provider-scoped model catalogue. */
export const qwenModelPresets = ['qwen3.8-max', 'qwen3.6-flash', 'qwen-plus', 'qwen3.5-plus'] as const;

export function filterQwenModels(query: string, candidates: readonly string[] = qwenModelPresets): readonly string[] {
  return filterModelCandidates(query, candidates);
}
