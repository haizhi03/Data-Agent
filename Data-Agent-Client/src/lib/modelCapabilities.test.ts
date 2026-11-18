import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ModelSetupEditor } from '../components/ai/ModelSetupEditor';
import { changeSetupConnection, profileOptions, setupConnection, setupFromModel } from './modelSetup';
import { inferProvider, selectProvider } from './modelProviderPresets';
import { specificationsForProfile } from './modelSpecifications';
import { taskModelOptions } from './taskModelOptions';
import type { ManagedModel, ModelConnection, ModelProfile, ModelSpecification } from '../types/modelManagement';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
const kimi: ModelProfile = {
  apiStyle: 'OPENAI_CHAT_COMPLETIONS', compatibilityProfile: 'kimi-chat-v1', capabilityProfile: 'kimi-reasoning-tools-v1',
  thinkingModes: ['ENABLED'], resource: 'chat/completions', optionRules: {
    reasoningEfforts: ['low', 'high', 'max'], defaultReasoningEffort: 'max', minThinkingBudget: null, thinkingBudgetRequired: false,
    temperatureModes: [], maxTemperature: 1, preserveThinking: true, defaultMaxOutputTokens: 16384,
    testMaxOutputTokens: 16384, minimumThinkingOutputTokens: 16384,
  },
};
const specifications: ModelSpecification[] = ['kimi-k3.1-pro', 'kimi-k3'].map((modelId) => ({ modelId, aliases: [],
  contextTokens: 1048576, maxOutputTokens: 1048576, maxThinkingOutputTokens: 1048576,
  sourceUrl: 'https://platform.kimi.com/docs/api/chat', verifiedAt: '2026-11-18',
  compatibilityProfile: kimi.compatibilityProfile, capabilityProfile: kimi.capabilityProfile,
}));
const connection: ModelConnection = { id: 'kimi-connection', code: 'kimi', name: 'Kimi', provider: 'MOONSHOT', apiStyle: kimi.apiStyle,
  endpoint: 'https://api.moonshot.cn/v1', requestUrl: '', compatibilityProfile: kimi.compatibilityProfile,
  authType: 'API_KEY', hasCredential: true, credentialKind: 'ENCRYPTED', credentialVersion: 1, version: 2, enabled: true };

function render(profile = kimi) {
  const setup = { ...setupFromModel([profile], connection), modelId: 'kimi-k3.1-pro' };
  return renderToStaticMarkup(createElement(ModelSetupEditor, { value: setup, profiles: [profile], specifications,
    hasCredential: true, credentialStorageReady: true, secret: '', busy: false,
    onChange: () => {}, onSecretChange: () => {}, onSubmit: () => {}, onCancel: () => {}, testConnection: vi.fn() }));
}

describe('provider capabilities and model catalogue', () => {
  it('switches to Kimi with its endpoint, required thinking and provider-specific effort defaults', () => {
    const initial = setupFromModel([]);
    const selected = selectProvider(setupConnection(initial), 'MOONSHOT', [kimi]);
    const setup = changeSetupConnection(initial, selected, [kimi]);
    expect(setup).toMatchObject({ provider: 'MOONSHOT', endpoint: 'https://api.moonshot.cn/v1', capabilityProfile: kimi.capabilityProfile,
      defaultOptions: { thinkingMode: 'ENABLED', reasoningEffort: 'max', maxOutputTokens: null } });
    expect(inferProvider({ provider: 'KIMI', compatibilityProfile: kimi.compatibilityProfile })).toBe('MOONSHOT');
    expect(profileOptions(kimi)).toEqual(setup.defaultOptions);
  });
  it('renders both Kimi models and supported controls without Qwen candidates or incompatible parameters', () => {
    const markup = render();
    expect(markup).toContain('>kimi-k3.1-pro</span>');expect(markup).toContain('>kimi-k3</span>');
    expect(markup).not.toContain('qwen3.8-max');
    expect(markup).toContain('>low</option>');expect(markup).toContain('>high</option>');expect(markup).toContain('>max</option>');
    expect(markup).not.toContain('>medium</option>');
    expect(markup).not.toContain('model_settings.temperature');expect(markup).not.toContain('model_settings.thinking_budget');
    expect(markup).toMatch(/<select[^>]*disabled=""[^>]*><option value="ENABLED"/);
    expect(markup).toMatch(/<input[^>]*max="1048576"[^>]*placeholder="model_settings.setup.context_placeholder"/);
    expect(markup).toMatch(/<input[^>]*max="1000000"[^>]*placeholder="model_settings.setup.output_placeholder"/);
  });
  it('renders new effort options from metadata without knowing the capability name', () => {
    const custom = { ...kimi, capabilityProfile: 'fixture-tools-v9', optionRules: { ...kimi.optionRules!,
      reasoningEfforts: ['minimal', 'deep'], defaultReasoningEffort: 'deep' } };
    const markup = render(custom);
    expect(markup).toContain('>minimal</option>');expect(markup).toContain('>deep</option>');
    expect(markup).not.toContain('>max</option>');
  });
  it('scopes task candidates to the connection and retains custom model IDs', () => {
    const model: ManagedModel = { id: 'configured-kimi', code: 'kimi', connectionId: connection.id, name: 'Kimi', modelId: 'kimi-k3.1-pro',
      capabilityProfile: kimi.capabilityProfile, defaultOptions: profileOptions(kimi), maxContextTokens: 1048576, memoryThreshold: 24000,
      chatVisible: true, enabled: true, available: true, version: 1 };
    const mixed = [...specifications, { ...specifications[0], modelId: 'qwen-plus', compatibilityProfile: 'qwen-chat-v1' }];
    const options = taskModelOptions([connection], [model], mixed, { purpose: 'PLANNER', modelConfigId: model.id, version: 1 });
    expect(options).toHaveLength(2);
    expect(options[1]).toMatchObject({ kind: 'preset', modelId: 'kimi-k3', connectionId: connection.id, connectionExpectedVersion: 2 });
    expect(specificationsForProfile(mixed, 'qwen-chat-v1').map((spec) => spec.modelId)).toEqual(['qwen-plus']);
    expect(taskModelOptions([connection], [{ ...model, modelId: 'account-custom-model' }], mixed,
      { purpose: 'MAIN_CHAT', modelConfigId: model.id, version: 1 })).toHaveLength(3);
  });
});
