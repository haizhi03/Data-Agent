import type { ModelSpecification, ThinkingMode } from '../types/modelManagement';

export function findModelSpecification(specifications: ModelSpecification[], modelId: string): ModelSpecification | undefined {
  const normalized = modelId.trim().toLowerCase();
  return specifications.find((specification) => specification.modelId === normalized || specification.aliases.includes(normalized));
}

export function modelOutputCeiling(specification: ModelSpecification, thinking: ThinkingMode): number {
  return thinking === 'DISABLED' ? specification.maxOutputTokens : specification.maxThinkingOutputTokens;
}
