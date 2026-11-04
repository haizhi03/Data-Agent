import { beforeEach, describe, expect, it, vi } from 'vitest';
import http from '../lib/http';
import { aiService } from './ai.service';
import { FALLBACK_MODELS, defaultModelSelection, modelRequest } from '../constants/models';

vi.mock('../lib/http', () => ({ default: { get: vi.fn() } }));

describe('workspace model availability', () => {
  beforeEach(() => vi.resetAllMocks());

  it('preserves distinct configuration IDs for identical upstream names', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [
      { modelName: 'same-model', modelConfigId: 'config-a', modelId: 'qwen3.8-max', name: 'Primary', provider: 'OPENAI', compatibilityProfile: 'qwen-chat-v1', defaultModel: true,
        thinkingMode: 'DISABLED', thinkingModes: ['DISABLED', 'ENABLED'] },
      { modelName: 'same-model', modelConfigId: 'config-b', name: 'Backup' },
    ] });
    const options = await aiService.getModels();
    expect(options.map((m) => m.modelName)).toEqual(['config-a', 'config-b']);
    expect(modelRequest('config-b', options)).toEqual({ modelConfigId: 'config-b' });
    expect(options[0].defaultModel).toBe(true);
    expect(options[0]).toMatchObject({ modelId: 'qwen3.8-max', provider: 'OPENAI', compatibilityProfile: 'qwen-chat-v1' });
    expect(modelRequest('config-a', options)).toEqual({ modelConfigId: 'config-a' });
    expect(options[0]).toMatchObject({ thinkingMode: 'DISABLED', thinkingModes: ['DISABLED', 'ENABLED'] });
    expect(defaultModelSelection([...options].reverse())).toBe('config-a');
    expect(defaultModelSelection([])).toBe('');
  });

  it('does not reuse a previous workspace list or supply callable hardcoded models on failure', async () => {
    vi.mocked(http.get).mockResolvedValueOnce({ data: [{ modelName: 'old-model' }] }).mockRejectedValueOnce(new Error('unavailable'));
    expect(await aiService.getModels()).toHaveLength(1);
    expect(await aiService.getModels()).toEqual([]);
    expect(FALLBACK_MODELS).toEqual([]);
    expect(modelRequest('old-model', [])).toEqual({ model: '__unavailable__' });
  });

  it('retains legacy server compatibility without inventing model capabilities', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [{ modelName: 'legacy', memoryThreshold: -1 }] });
    const options = await aiService.getModels();
    expect(modelRequest('legacy', options)).toEqual({ model: 'legacy' });
    expect(options[0].supportThinking).toBe(false);
    expect(options[0].memoryThreshold).toBeNull();
  });

  it('retains all four server-authorized Qwen choices and submits the selected preset configuration ID', async () => {
    const models = ['qwen3.8-max', 'qwen3.6-flash', 'qwen-plus', 'qwen3.5-plus'];
    vi.mocked(http.get).mockResolvedValue({ data: models.map((modelId, index) => ({
      modelName: index ? `qwen-preset/source-config/${modelId}` : 'source-config',
      modelConfigId: index ? `qwen-preset/source-config/${modelId}` : 'source-config',
      modelId, name: 'qwen', provider: 'QWEN', compatibilityProfile: 'qwen-chat-v1', maxContextTokens: 1000000,
    })) });
    const options = await aiService.getModels();
    expect(options.map((option) => option.modelId)).toEqual(models);
    expect(options.map((option) => option.maxContextTokens)).toEqual([1000000, 1000000, 1000000, 1000000]);
    expect(modelRequest(options[3].modelName, options)).toEqual({ modelConfigId: 'qwen-preset/source-config/qwen3.5-plus' });
  });
});
