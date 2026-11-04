import { describe, expect, it } from 'vitest';
import { chatModelLabel, groupChatModels } from './chatModelOptions';
import { modelRequest } from '../constants/models';
import type { ModelOption } from '../types/ai';

function model(id: string, provider?: string, extra: Partial<ModelOption> = {}): ModelOption {
  return { modelName: id, modelConfigId: id, provider, supportThinking: false, memoryThreshold: null, maxContextTokens: null, ...extra };
}

describe('chat model provider groups', () => {
  it('groups Qwen aliases and older OpenAI-labeled Qwen connections together', () => {
    const options = [model('one', 'QWEN'), model('openai', 'OPENAI'), model('two', 'ALIBABA_CLOUD'),
      model('three', 'DASHSCOPE'), model('four', 'OPENAI', { compatibilityProfile: 'qwen-chat-v1' })];
    const groups = groupChatModels(options);
    expect(groups.map((group) => group.id)).toEqual(['QWEN', 'OPENAI']);
    expect(groups[0].models.map((option) => option.modelName)).toEqual(['one', 'two', 'three', 'four']);
    expect(groups[0].labelKey).toBe('model_settings.providers.QWEN');
    expect(options.map((option) => option.modelName)).toEqual(['one', 'openai', 'two', 'three', 'four']);
  });

  it('keeps distinct vendors using OpenAI-compatible APIs separate and supports missing metadata', () => {
    const groups = groupChatModels([model('claude', 'CLAUDE'), model('anthropic', 'ANTHROPIC'),
      model('deepseek', 'DeepSeek', { compatibilityProfile: 'openai-chat-v1' }), model('other', 'Another vendor'),
      model('no-provider'), model('custom', 'CUSTOM')]);
    expect(groups.map((group) => group.id)).toEqual(['ANTHROPIC', 'DEEPSEEK', 'ANOTHER VENDOR', 'UNSPECIFIED', 'CUSTOM']);
    expect(groups[1].label).toBe('DeepSeek');
    expect(groups[1].labelKey).toBeUndefined();
    expect(groups[3].labelKey).toBe('chat_model_selector.other_provider');
    expect(groups[4].labelKey).toBe('model_settings.providers.CUSTOM');
  });

  it('displays upstream IDs and preserves separate selections for connections sharing the same model', () => {
    const options = [model('primary-id', 'QWEN', { modelId: 'qwen3.8-max', name: '主连接' }),
      model('backup-id', 'QWEN', { modelId: 'qwen3.8-max', name: '备用连接' })];
    expect(groupChatModels(options)[0].models).toEqual(options);
    expect(options.map(chatModelLabel)).toEqual(['qwen3.8-max', 'qwen3.8-max']);
    expect(modelRequest('backup-id', options)).toEqual({ modelConfigId: 'backup-id' });
    expect(chatModelLabel(model('old-config', undefined, { name: '旧配置' }))).toBe('旧配置');
    expect(chatModelLabel(model('legacy-model'))).toBe('legacy-model');
  });

  it('only exposes models from the workspace availability list and has no hardcoded callable fallbacks', () => {
    expect(groupChatModels([])).toEqual([]);
    expect(groupChatModels([model('saved', 'QWEN')])[0].models).toHaveLength(1);
  });
});
