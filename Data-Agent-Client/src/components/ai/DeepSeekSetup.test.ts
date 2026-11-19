import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ModelSetupEditor } from './ModelSetupEditor';
import { changeSetupConnection, profileOptions, setupConnection, setupFromModel } from '../../lib/modelSetup';
import { inferProvider, selectProvider } from '../../lib/modelProviderPresets';
import type { ModelConnection, ModelProfile, ModelSpecification } from '../../types/modelManagement';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));

const profile: ModelProfile = {
  apiStyle: 'OPENAI_CHAT_COMPLETIONS', compatibilityProfile: 'deepseek-chat-v1', capabilityProfile: 'deepseek-thinking-tools-v1',
  thinkingModes: ['ENABLED', 'DISABLED'], resource: 'chat/completions',
  optionRules: { reasoningEfforts: ['low', 'high', 'max'], defaultReasoningEffort: 'high', minThinkingBudget: null, thinkingBudgetRequired: false,
    temperatureModes: ['DISABLED'], maxTemperature: 2, preserveThinking: true, defaultMaxOutputTokens: 16384,
    testMaxOutputTokens: 8192, minimumThinkingOutputTokens: 16384 },
};
const specifications: ModelSpecification[] = ['deepseek-flash', 'deepseek-v4-pro'].map((modelId) => ({ modelId, aliases: [],
  contextTokens: 1000000, maxOutputTokens: 393216, maxThinkingOutputTokens: 393216,
  sourceUrl: 'https://api-docs.deepseek.com/quick_start/pricing', verifiedAt: '2026-11-19',
  compatibilityProfile: 'deepseek-chat-v1', capabilityProfile: 'deepseek-thinking-tools-v1' }));
const connection: ModelConnection = { id: 'deepseek-connection', code: 'deepseek', name: 'DeepSeek', provider: 'DEEPSEEK',
  apiStyle: profile.apiStyle, endpoint: 'https://api.deepseek.com', requestUrl: '', compatibilityProfile: profile.compatibilityProfile,
  authType: 'API_KEY', hasCredential: true, credentialKind: 'ENCRYPTED', credentialVersion: 1, version: 2, enabled: true };

function render() {
  const setup = { ...setupFromModel([profile], connection), modelId: 'deepseek-v4-pro' };
  return renderToStaticMarkup(createElement(ModelSetupEditor, { value: setup, profiles: [profile], specifications, hasCredential: true,
    credentialStorageReady: true, secret: '', busy: false, onChange: () => {}, onSecretChange: () => {}, onSubmit: () => {},
    onCancel: () => {}, testConnection: vi.fn() }));
}

describe('DeepSeek provider setup', () => {
  it('switches to DeepSeek with its endpoint and always-on-by-default thinking', () => {
    const initial = setupFromModel([]);
    const selected = changeSetupConnection(initial, selectProvider(setupConnection(initial), 'DEEPSEEK', [profile]), [profile]);
    expect(selected).toMatchObject({ provider: 'DEEPSEEK', endpoint: 'https://api.deepseek.com',
      capabilityProfile: 'deepseek-thinking-tools-v1', defaultOptions: { thinkingMode: 'ENABLED', reasoningEffort: 'high', maxOutputTokens: null } });
    expect(inferProvider({ provider: 'DEEPSEEK', compatibilityProfile: 'deepseek-chat-v1' })).toBe('DEEPSEEK');
    expect(profileOptions(profile)).toEqual(selected.defaultOptions);
  });

  it('offers both models, three effort levels and a thinking switch that is not locked', () => {
    const markup = render();
    expect(markup).toContain('>deepseek-flash</span>');
    expect(markup).toContain('>deepseek-v4-pro</span>');
    expect(markup).not.toContain('qwen3.8-max');
    expect(markup).toContain('>low</option>');
    expect(markup).toContain('>high</option>');
    expect(markup).toContain('>max</option>');
    expect(markup).not.toMatch(/<select[^>]*disabled=""[^>]*><option value="ENABLED"/);
    expect(markup).toMatch(/<input[^>]*max="1000000"/);
  });

  it('keeps the compression threshold in the main form and explains the automatic share', () => {
    const markup = render();
    expect(markup).toContain('model_settings.threshold');
    expect(markup).toContain('model_settings.setup.threshold_hint');
    expect(markup).not.toContain('model_settings.temperature');
  });
});
