import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ModelSetupEditor } from './ModelSetupEditor';
import { setupFromModel } from '../../lib/modelSetup';
import type { ModelProfile, ModelSpecification } from '../../types/modelManagement';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
const profiles: ModelProfile[] = [{ apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1', capabilityProfile: 'responses-tools-v1', thinkingModes: ['DISABLED'], resource: 'responses' }];
function render(hasCredential: boolean) {
  return renderToStaticMarkup(createElement(ModelSetupEditor, { value: { ...setupFromModel(profiles), modelId: 'account-model-id',
    ...(hasCredential ? { connectionId: 'saved-connection', connectionExpectedVersion: 1, modelConfigId: 'saved-model', modelExpectedVersion: 1 } : {}) }, profiles,
    hasCredential, credentialStorageReady: true, secret: '', busy: false, onChange: () => {}, onSecretChange: () => {}, onSubmit: () => {}, onCancel: () => {}, testConnection: vi.fn() }));
}
describe('simple model setup form', () => {
  it('shows one editable model ID shared by saving and connection testing', () => {
    const markup = render(false);
    expect(markup.match(/value="account-model-id"/g)).toHaveLength(1);
    expect(markup).toContain('connection_test.action');
    expect(markup).not.toContain('connection_test.configured_model');expect(markup).not.toContain('connection_test.model_placeholder');
    expect(markup).not.toContain('model_settings.code');expect(markup).not.toContain('model_settings.connection</span>');
  });
  it('shows the endpoint and token limits directly while keeping API and thinking settings collapsed', () => {
    const markup = render(false);const details = markup.match(/<details[^>]*>/)?.[0];
    expect(details).toBeDefined();expect(details).not.toContain('open=');
    const start = markup.indexOf('<details');const end = markup.indexOf('</details>');
    expect(markup.slice(start, end)).toContain('model_settings.thinking');expect(markup.slice(start, end)).toContain('model_settings.setup.capability');
    expect(markup.indexOf('Endpoint')).toBeLessThan(markup.indexOf('value="account-model-id"'));
    expect(markup.indexOf('model_settings.context')).toBeLessThan(start);expect(markup.indexOf('model_settings.max_output')).toBeLessThan(start);
    expect(markup.indexOf('value="account-model-id"')).toBeLessThan(start);
  });
  it('allows keeping or replacing a saved key in the same form without reading it back', () => {
    const markup = render(true);
    expect(markup).toContain('type="password"');expect(markup).toContain('model_settings.setup.key_placeholder');
    expect(markup).toContain('model_settings.setup.show_key');expect(markup).toContain('aria-pressed="false"');
    expect(markup).not.toContain('model_settings.credential');
    expect(markup).not.toMatch(/type="submit"[^>]* disabled=""/);
  });
  it('leaves both token fields optional and blank with automatic placeholders and the output range', () => {
    const markup = render(false);
    const context = markup.match(/<input[^>]*placeholder="model_settings.setup.context_placeholder"[^>]*>/)?.[0];
    const output = markup.match(/<input[^>]*placeholder="model_settings.setup.output_placeholder"[^>]*>/)?.[0];
    expect(context).toBeDefined();expect(output).toBeDefined();
    expect(context).toContain('value=""');expect(context).not.toContain('required=');
    expect(output).toContain('value=""');expect(output).not.toContain('required=');
    expect(output).toContain('min="256"');expect(output).toContain('max="1000000"');
  });
  it('uses server model specifications for context and mode-specific output limits while preserving automatic fields', () => {
    const specification: ModelSpecification = { modelId: 'qwen3-max', aliases: ['qwen3-max-2026-01-23'], contextTokens: 262144,
      maxOutputTokens: 65536, maxThinkingOutputTokens: 32768, sourceUrl: 'https://help.aliyun.com/zh/model-studio/model-qwen3-max', verifiedAt: '2026-10-24' };
    const qwen: ModelProfile = { apiStyle: 'OPENAI_CHAT_COMPLETIONS', compatibilityProfile: 'qwen-chat-v1', capabilityProfile: 'qwen-tools-v1', thinkingModes: ['DISABLED', 'ENABLED'], resource: 'chat/completions' };
    for (const mode of ['DISABLED', 'ENABLED'] as const) {
      const markup = renderToStaticMarkup(createElement(ModelSetupEditor, { value: { ...setupFromModel([qwen]), provider: 'QWEN', apiStyle: qwen.apiStyle,
        compatibilityProfile: qwen.compatibilityProfile, capabilityProfile: qwen.capabilityProfile, modelId: 'qwen3-max-2026-01-23', defaultOptions: { thinkingMode: mode, maxOutputTokens: null } },
        profiles: [qwen], specifications: [specification], hasCredential: false, credentialStorageReady: true, secret: '', busy: false,
        onChange: () => {}, onSecretChange: () => {}, onSubmit: () => {}, onCancel: () => {}, testConnection: vi.fn() }));
      const context = markup.match(/<input[^>]*placeholder="model_settings.setup.context_placeholder"[^>]*>/)?.[0];
      const output = markup.match(/<input[^>]*placeholder="model_settings.setup.output_placeholder"[^>]*>/)?.[0];
      expect(context).toContain('max="262144"');expect(context).toContain('value=""');
      expect(output).toContain(`max="${mode === 'DISABLED' ? 65536 : 32768}"`);expect(output).toContain('value=""');
      expect(markup).toContain('setup.known_context_hint');expect(markup).toContain('setup.known_output_hint');
      expect(markup).toContain(specification.sourceUrl);expect(markup).toContain('setup.candidate_thinking_limit');
    }
  });
});
