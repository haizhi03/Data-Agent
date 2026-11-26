import type { ModelSpecification, ThinkingMode } from '../types/modelManagement';

/** Share of the context window used as the automatic compression trigger; mirrors the server policy. */
export const AUTO_THRESHOLD_RATIO = 0.7;

/** Vendors quote budgets as "1M" / "384K"; show the same shorthand instead of raw token counts. */
export function formatTokenBudget(tokens: number): string {
  if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
  if (tokens >= 1_024) return `${Math.round(tokens / 1_024)}K`;
  return String(tokens);
}

export function filterModelCandidates(query: string, candidates: readonly string[]): readonly string[] {
  const normalize = (value: string) => value.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
  const normalized = normalize(query);
  return candidates.filter((model) => normalize(model).includes(normalized));
}

export function findModelSpecification(specifications: ModelSpecification[], modelId: string): ModelSpecification | undefined {
  const normalized = modelId.trim().toLowerCase();
  return specifications.find((specification) => specification.modelId === normalized || specification.aliases.includes(normalized));
}

export function modelOutputCeiling(specification: ModelSpecification, thinking: ThinkingMode): number {
  return Math.min(1000000, thinking === 'DISABLED' ? specification.maxOutputTokens : specification.maxThinkingOutputTokens);
}

export function specificationsForProfile(specifications: ModelSpecification[], compatibility: string): ModelSpecification[] {
  return specifications.filter((specification) => specification.compatibilityProfile
    ? specification.compatibilityProfile === compatibility : compatibility === 'qwen-chat-v1');
}
