import type { ManagedModel, ModelConnection, ModelSpecification, PurposeBinding } from '../types/modelManagement';
import { qwenModelPresets } from './qwenModelPresets';

export type TaskModelOption = { value: string; label: string } & (
  { kind: 'model'; modelConfigId: string } |
  { kind: 'preset'; connectionId: string; connectionExpectedVersion: number; modelId: string }
);

/** Presets use the configured connection; only an explicit selection creates a saved model variant. */
export function taskModelOptions(connections: ModelConnection[], models: ManagedModel[], specifications: ModelSpecification[], binding: PurposeBinding): TaskModelOption[] {
  const result: TaskModelOption[] = [];
  const included = new Set<string>();
  const available = models.filter((model) => model.available && (binding.purpose !== 'MAIN_CHAT' || model.chatVisible));
  const candidates = specifications.length ? specifications.map((specification) => specification.modelId) : qwenModelPresets;
  for (const connection of connections) {
    if (!connection.enabled || !connection.hasCredential || connection.compatibilityProfile !== 'qwen-chat-v1'
      || !models.some((model) => !model.taskPreset && model.available && model.connectionId === connection.id)) continue;
    for (const modelId of candidates) {
      const matches = available.filter((model) => model.connectionId === connection.id && model.modelId === modelId);
      const existing = matches.find((model) => model.id === binding.modelConfigId) ?? matches.find((model) => !model.taskPreset) ?? matches[0];
      const label = `${connection.name} · ${modelId}`;
      if (existing) {
        included.add(existing.id);result.push({ kind: 'model', value: `model/${existing.id}`, label, modelConfigId: existing.id });
      } else result.push({ kind: 'preset', value: `preset/${connection.id}/${modelId}`, label, connectionId: connection.id,
        connectionExpectedVersion: connection.version, modelId });
    }
  }
  for (const model of available) {
    if (included.has(model.id) || (model.taskPreset && model.id !== binding.modelConfigId)) continue;
    result.push({ kind: 'model', value: `model/${model.id}`, label: `${model.name} · ${model.modelId}`, modelConfigId: model.id });
  }
  return result;
}
