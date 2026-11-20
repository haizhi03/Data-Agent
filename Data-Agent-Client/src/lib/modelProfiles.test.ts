import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ModelSetupEditor } from '../components/ai/ModelSetupEditor';
import { profileOptions, setupFromModel } from './modelSetup';
import { specificationsForProfile } from './modelSpecifications';
import type { ModelProfile, ModelSpecification } from '../types/modelManagement';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
const profile: ModelProfile = {
  apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1', capabilityProfile: 'responses-reasoning-v1',
  thinkingModes: ['ENABLED'], resource: 'responses', optionRules: {
    reasoningEfforts: ['low', 'medium', 'high'], defaultReasoningEffort: 'medium', minThinkingBudget: null, thinkingBudgetRequired: false,
    temperatureModes: [], maxTemperature: 2, preserveThinking: false, defaultMaxOutputTokens: 4096,
    testMaxOutputTokens: 8192, minimumThinkingOutputTokens: 8192,
  },
};
const render = (selected: ModelProfile) => renderToStaticMarkup(createElement(ModelSetupEditor, {
  value: { ...setupFromModel([selected]), capabilityProfile: selected.capabilityProfile, modelId: 'fixture-model' },
  profiles: [selected], hasCredential: false, credentialStorageReady: true, secret: '', busy: false,
  onChange: () => {}, onSecretChange: () => {}, onSubmit: () => {}, onCancel: () => {}, testConnection: vi.fn(),
}));

describe('shared model capability metadata', () => {
  it('derives reasoning defaults and controls from server rules', () => {
    expect(profileOptions(profile)).toEqual({ thinkingMode: 'ENABLED', maxOutputTokens: null, reasoningEffort: 'medium' });
    const markup = render(profile);
    for (const effort of profile.optionRules!.reasoningEfforts) expect(markup).toContain(`>${effort}</option>`);
    expect(markup).not.toContain('model_settings.temperature');expect(markup).not.toContain('model_settings.thinking_budget');
  });
  it('reads new effort choices without branching on a capability name', () => {
    const selected = { ...profile, capabilityProfile: 'fixture-reasoning-profile', optionRules: { ...profile.optionRules!,
      reasoningEfforts: ['minimal', 'deep'], defaultReasoningEffort: 'deep' } };
    const markup = render(selected);
    expect(markup).toContain('>minimal</option>');expect(markup).toContain('>deep</option>');
    expect(markup).not.toContain('>medium</option>');
  });
  it('retains required thinking-budget defaults supplied by a profile', () => {
    const selected = { ...profile, optionRules: { ...profile.optionRules!, reasoningEfforts: [], defaultReasoningEffort: null,
      minThinkingBudget: 1024, thinkingBudgetRequired: true } };
    expect(profileOptions(selected)).toMatchObject({ thinkingMode: 'ENABLED', thinkingBudget: 1024 });
    expect(render(selected)).toMatch(/<input[^>]*min="1024"[^>]*required=""/);
  });
  it('scopes model suggestions by compatibility while accepting legacy catalogue responses', () => {
    const specification: ModelSpecification = { modelId: 'qwen-plus', aliases: [], contextTokens: 1000000,
      maxOutputTokens: 32768, maxThinkingOutputTokens: 32768, sourceUrl: 'https://example.com/model', verifiedAt: '2026-11-19' };
    const catalogue = [specification, { ...specification, modelId: 'gateway-model', compatibilityProfile: 'gateway-chat-v1' }];
    expect(specificationsForProfile(catalogue, 'qwen-chat-v1').map((entry) => entry.modelId)).toEqual(['qwen-plus']);
    expect(specificationsForProfile(catalogue, 'gateway-chat-v1').map((entry) => entry.modelId)).toEqual(['gateway-model']);
  });
});
