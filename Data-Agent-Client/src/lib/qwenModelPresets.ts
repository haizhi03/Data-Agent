/** Initial user-requested candidates, independent of upstream discovery or account permissions. */
export const qwenModelPresets = ['qwen3.8-max', 'qwen3.6-flash', 'qwen-plus', 'qwen3.5-plus'] as const;

export function filterQwenModels(query: string, candidates: readonly string[] = qwenModelPresets): readonly string[] {
  const normalized = query.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
  return candidates.filter((model) => model.replace(/[^a-z0-9]/g, '').includes(normalized));
}
