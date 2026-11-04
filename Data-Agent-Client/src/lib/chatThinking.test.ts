import { describe, expect, it } from 'vitest';
import type { ModelOption } from '../types/ai';
import { chatThinkingRequest, chatThinkingState } from './chatThinking';

const qwen: ModelOption = { modelName: 'qwen-config', supportThinking: true, thinkingMode: 'DISABLED',
  thinkingModes: ['DISABLED', 'ENABLED'], memoryThreshold: null, maxContextTokens: null };

describe('conversation thinking selection', () => {
  it('uses the selected models supported modes and current default', () => {
    expect(chatThinkingState(qwen)).toMatchObject({ visible: true, mode: 'DISABLED', enabled: false, canToggle: true });
    expect(chatThinkingRequest(qwen, 'ENABLED')).toEqual({ thinkingMode: 'ENABLED' });
    expect(chatThinkingRequest(qwen, 'DISABLED')).toEqual({ thinkingMode: 'DISABLED' });
    expect(chatThinkingRequest({ ...qwen, thinkingMode: 'ENABLED' })).toEqual({ thinkingMode: 'ENABLED' });
  });

  it('hides and omits thinking when switching to an unsupported, unavailable or older-server model', () => {
    for (const option of [undefined, { ...qwen, supportThinking: false, thinkingModes: ['DISABLED'] as const }, { ...qwen, thinkingModes: undefined }]) {
      const model = option ? { ...option, thinkingModes: option.thinkingModes ? [...option.thinkingModes] : undefined } : undefined;
      expect(chatThinkingState(model, 'ENABLED').visible).toBe(false);
      expect(chatThinkingRequest(model, 'ENABLED')).toEqual({});
    }
    expect(chatThinkingRequest(qwen, 'ENABLED')).toEqual({ thinkingMode: 'ENABLED' });
  });

  it('honors mandatory and adaptive thinking while rejecting incompatible stored preferences', () => {
    const mandatory = { ...qwen, thinkingMode: 'ENABLED' as const, thinkingModes: ['ENABLED' as const] };
    expect(chatThinkingState(mandatory, 'DISABLED')).toMatchObject({ visible: true, canToggle: false, enabled: true });
    expect(chatThinkingRequest(mandatory, 'DISABLED')).toEqual({ thinkingMode: 'ENABLED' });
    const adaptive = { ...qwen, thinkingModes: ['DISABLED' as const, 'ADAPTIVE' as const] };
    expect(chatThinkingState(adaptive, 'ADAPTIVE')).toMatchObject({ mode: 'ADAPTIVE', enabledMode: 'ADAPTIVE', canToggle: true });
    expect(chatThinkingRequest(qwen, 'ADAPTIVE')).toEqual({ thinkingMode: 'DISABLED' });
  });
});
