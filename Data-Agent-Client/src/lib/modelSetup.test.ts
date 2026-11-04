import { describe, expect, it } from 'vitest';
import { changeSetupConnection, setupConnection, setupFromModel, setupSaveBody } from './modelSetup';
import type { ManagedModel, ModelConnection, ModelProfile, PurposeBinding } from '../types/modelManagement';
const profiles: ModelProfile[] = [
  { apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1', capabilityProfile: 'responses-tools-v1', thinkingModes: ['DISABLED'], resource: 'responses' },
  { apiStyle: 'OPENAI_CHAT_COMPLETIONS', compatibilityProfile: 'qwen-chat-v1', capabilityProfile: 'qwen-tools-v1', thinkingModes: ['DISABLED', 'ENABLED'], resource: 'chat/completions' },
];
const connection: ModelConnection = { id: 'connection-id', code: 'legacy-connection-code', name: 'qwen3.8-max', provider: 'QWEN',
  apiStyle: 'OPENAI_CHAT_COMPLETIONS', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1', compatibilityProfile: 'qwen-chat-v1',
  requestUrl: '', authType: 'API_KEY', hasCredential: true, credentialKind: 'ENCRYPTED', credentialVersion: 2, version: 3, enabled: true };
const model: ManagedModel = { id: 'model-id', code: 'legacy-model-code', connectionId: connection.id, name: 'Work', modelId: 'qwen3.8-max',
  capabilityProfile: 'qwen-tools-v1', defaultOptions: { thinkingMode: 'DISABLED', maxOutputTokens: 4096 }, maxContextTokens: 32768,
  memoryThreshold: 24000, chatVisible: true, enabled: true, available: true, version: 4 };

describe('one model configuration', () => {
  it('creates a complete form without asking for configuration codes', () => {
    const setup = setupFromModel(profiles);
    expect(setup).toMatchObject({ apiStyle: 'OPENAI_RESPONSES', capabilityProfile: 'responses-tools-v1', enabled: true, chatVisible: true });
    expect(setup).not.toHaveProperty('code');expect(setup).not.toHaveProperty('connectionId');expect(setup.modelId).toBe('');
    expect(setup.maxContextTokens).toBeNull();expect(setup.defaultOptions.maxOutputTokens).toBeNull();expect(setup.memoryThreshold).toBeNull();
  });
  it('preserves existing IDs, versions and model options while keeping internal codes out of the form', () => {
    const setup = setupFromModel(profiles, connection, model);
    expect(setup).toMatchObject({ connectionId: 'connection-id', connectionExpectedVersion: 3, modelConfigId: 'model-id', modelExpectedVersion: 4, modelId: 'qwen3.8-max' });
    expect(JSON.stringify(setup)).not.toContain('legacy-');expect(setup).not.toHaveProperty('apiKey');
    expect(setupFromModel(profiles, { ...connection, enabled: false }, model).enabled).toBe(false);
    setup.defaultOptions.maxOutputTokens = 1000;expect(model.defaultOptions.maxOutputTokens).toBe(4096);
  });
  it('does not confuse a connection display name with the actual upstream model ID', () => {
    expect(setupFromModel(profiles, connection).modelId).toBe('');
    expect(setupFromModel(profiles, connection, { ...model, modelId: '1' }).modelId).toBe('1');
    const fixed = { ...setupFromModel(profiles, connection, model), modelId: 'qwen3.8-max-0902' };
    expect(setupSaveBody(fixed, '', []).modelId).toBe('qwen3.8-max-0902');
  });
  it('resets incompatible advanced options when changing APIs while preserving edit versions', () => {
    const setup = { ...setupFromModel(profiles, connection, model), capabilityProfile: 'responses-reasoning-v1', defaultOptions: { thinkingMode: 'ENABLED' as const, reasoningEffort: 'high', temperature: 1 } };
    const changed = changeSetupConnection(setup, { ...setupConnection(setup), apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1' }, profiles);
    expect(changed.defaultOptions).toEqual({ thinkingMode: 'DISABLED', maxOutputTokens: null });
    expect(changed.capabilityProfile).toBe('responses-tools-v1');expect(changed.connectionExpectedVersion).toBe(3);expect(changed.modelExpectedVersion).toBe(4);
  });
  it('keeps automatic choices blank after reload and submits null instead of materialized runtime limits', () => {
    const autoModel = { ...model, maxContextTokens: 128000, defaultOptions: { ...model.defaultOptions, maxOutputTokens: 8192 },
      tokenLimits: { maxContextTokens: null, maxOutputTokens: null } };
    const setup = setupFromModel(profiles, connection, autoModel);
    expect(setup.maxContextTokens).toBeNull();expect(setup.defaultOptions.maxOutputTokens).toBeNull();
    const submitted = JSON.parse(JSON.stringify(setupSaveBody(setup, '', [])));
    expect(submitted.maxContextTokens).toBeNull();expect(submitted.defaultOptions.maxOutputTokens).toBeNull();
    const manual = setupFromModel(profiles, connection, { ...autoModel, tokenLimits: { maxContextTokens: 64000, maxOutputTokens: 10000 } });
    expect(manual.maxContextTokens).toBe(64000);expect(manual.defaultOptions.maxOutputTokens).toBe(10000);
    const switched = changeSetupConnection(setup, { ...setupConnection(setup), apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1' }, profiles);
    expect(switched.defaultOptions.maxOutputTokens).toBeNull();
  });
  it('only submits a newly entered key and pins all default-binding versions when requested', () => {
    const setup = { ...setupFromModel(profiles, connection, model), apiKey: 'stale-must-not-send', bindingVersions: { MAIN_CHAT: 999 } };
    expect(setupSaveBody(setup, '', [])).not.toHaveProperty('apiKey');expect(setupSaveBody(setup, '', [])).not.toHaveProperty('bindingVersions');
    const bindings: PurposeBinding[] = [{ purpose: 'MAIN_CHAT', modelConfigId: model.id, version: 2 }, { purpose: 'MEMORY_WRITER', modelConfigId: model.id, version: 3 }];
    expect(setupSaveBody({ ...setup, makeDefault: true }, 'new-fixture-key', bindings)).toMatchObject({ apiKey: 'new-fixture-key', bindingVersions: { MAIN_CHAT: 2, MEMORY_WRITER: 3 } });
  });
});
