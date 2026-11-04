import type { ModelOption } from '../types/ai';

export interface ChatModelGroup {
  id: string;
  label: string;
  labelKey?: string;
  models: ModelOption[];
}

/** Display the actual model, while retaining configuration IDs as selection values. */
export function chatModelLabel(model: ModelOption): string {
  return model.modelId?.trim() || model.name?.trim() || model.modelName;
}

function providerGroup(model: ModelOption): Omit<ChatModelGroup, 'models'> {
  const label = model.provider?.trim() ?? '';
  let id = label.toUpperCase();
  // Preserve custom vendor labels even when they use another vendor's wire protocol.
  if (!id || ['OPENAI', 'QWEN', 'DASHSCOPE', 'ALIBABA_CLOUD', 'ANTHROPIC', 'CLAUDE'].includes(id)) {
    if (model.compatibilityProfile === 'qwen-chat-v1') id = 'QWEN';
    else if (model.compatibilityProfile === 'anthropic-messages-v1') id = 'ANTHROPIC';
  }
  if (['QWEN', 'DASHSCOPE', 'ALIBABA_CLOUD'].includes(id)) id = 'QWEN';
  if (id === 'CLAUDE') id = 'ANTHROPIC';
  if (['OPENAI', 'QWEN', 'ANTHROPIC', 'CUSTOM'].includes(id)) {
    return { id, label, labelKey: `model_settings.providers.${id}` };
  }
  return { id: id || 'UNSPECIFIED', label, ...(!id ? { labelKey: 'chat_model_selector.other_provider' } : {}) };
}

/** Group only models authorized by the workspace availability API; never invent callable presets. */
export function groupChatModels(models: ModelOption[]): ChatModelGroup[] {
  const groups = new Map<string, ChatModelGroup>();
  for (const model of models) {
    const provider = providerGroup(model);
    let group = groups.get(provider.id);
    if (!group) {
      group = { ...provider, models: [] };
      groups.set(provider.id, group);
    }
    group.models.push(model);
  }
  return [...groups.values()];
}
