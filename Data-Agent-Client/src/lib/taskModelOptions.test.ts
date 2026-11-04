import { describe, expect, it } from 'vitest';
import { taskModelOptions } from './taskModelOptions';
import { qwenModelPresets } from './qwenModelPresets';
import type { ManagedModel, ModelConnection, PurposeBinding } from '../types/modelManagement';

const connection: ModelConnection = { id: 'connection-one', code: 'qwen', name: 'Work Qwen', provider: 'QWEN', apiStyle: 'OPENAI_CHAT_COMPLETIONS',
  endpoint: 'https://example.com/v1', requestUrl: '', compatibilityProfile: 'qwen-chat-v1', authType: 'API_KEY', hasCredential: true,
  credentialKind: 'ENCRYPTED', credentialVersion: 1, version: 3, enabled: true };
const model: ManagedModel = { id: 'configured-model', code: 'qwen', name: 'Qwen', connectionId: connection.id, modelId: 'qwen3.8-max',
  capabilityProfile: 'qwen-tools-v1', defaultOptions: { thinkingMode: 'DISABLED' }, maxContextTokens: 1000000, memoryThreshold: 24000,
  chatVisible: true, enabled: true, available: true, version: 1 };
const binding: PurposeBinding = { purpose: 'MAIN_CHAT', modelConfigId: model.id, version: 1 };

describe('task choices from one configured Qwen connection', () => {
  it('offers all four models for every task without requiring four saved configurations', () => {
    for (const purpose of ['MAIN_CHAT', 'EXPLORER', 'PLANNER', 'COMPRESSION', 'MEMORY_WRITER'] as const) {
      const options = taskModelOptions([connection], [model], [], { ...binding, purpose });
      expect(options).toHaveLength(4);expect(options.map((option) => option.label)).toEqual(qwenModelPresets.map((id) => `Work Qwen · ${id}`));
      expect(options[0]).toMatchObject({ kind: 'model', modelConfigId: model.id });
      expect(options[1]).toMatchObject({ kind: 'preset', connectionId: connection.id, connectionExpectedVersion: 3, modelId: 'qwen3.6-flash' });
    }
  });
  it('reuses a selected saved variant without duplicate entries and allows main-chat promotion', () => {
    const variant = { ...model, id: 'task-variant', name: 'qwen3.6-flash', modelId: 'qwen3.6-flash', taskPreset: true, chatVisible: false };
    const options = taskModelOptions([connection], [model, variant], [], { ...binding, purpose: 'EXPLORER', modelConfigId: variant.id });
    expect(options).toHaveLength(4);expect(options[1]).toMatchObject({ kind: 'model', modelConfigId: variant.id });
    expect(taskModelOptions([connection], [model, variant], [], binding)[1].kind).toBe('preset');
  });
  it('excludes disabled or keyless Qwen connections and does not expand other providers', () => {
    expect(taskModelOptions([{ ...connection, enabled: false }], [{ ...model, available: false }], [], binding)).toEqual([]);
    expect(taskModelOptions([{ ...connection, hasCredential: false }], [{ ...model, available: false }], [], binding)).toEqual([]);
    const options = taskModelOptions([{ ...connection, compatibilityProfile: 'openai-chat-v1', provider: 'OPENAI' }], [model], [], binding);
    expect(options).toHaveLength(1);expect(options[0].kind).toBe('model');
  });
  it('keeps multiple connections distinct and pins each connection version', () => {
    const second = { ...connection, id: 'connection-two', name: 'Personal Qwen', version: 9 };
    const options = taskModelOptions([connection, second], [model, { ...model, id: 'second-model', connectionId: second.id }], [], binding);
    expect(options).toHaveLength(8);expect(new Set(options.map((option) => option.value)).size).toBe(8);
    expect(options[5]).toMatchObject({ connectionId: second.id, connectionExpectedVersion: 9 });
  });
  it('preserves custom models and currently bound variants whose original configuration moved', () => {
    const custom = { ...model, modelId: 'account-custom-model' };
    expect(taskModelOptions([connection], [custom], [], binding)).toHaveLength(5);
    const current = { ...model, taskPreset: true };
    const options = taskModelOptions([connection], [current], [], binding);
    expect(options).toHaveLength(1);expect(options[0]).toMatchObject({ kind: 'model', modelConfigId: current.id });
  });
});
