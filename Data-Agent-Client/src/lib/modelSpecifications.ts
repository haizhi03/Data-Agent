import type { ModelSpecification, ThinkingMode } from '../types/modelManagement';

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
