import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ModelConnectionFields } from '../components/ai/ModelConnectionFields';
import type { ConnectionInput, ModelProfile } from '../types/modelManagement';
import { connectionRequestUrl, inferProvider, providerApis, selectProvider, selectProviderApi, type ProviderPreset } from './modelProviderPresets';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
const profiles: ModelProfile[] = [
  { apiStyle: 'OPENAI_CHAT_COMPLETIONS', compatibilityProfile: 'openai-chat-v1', capabilityProfile: 'text-tools-v1', thinkingModes: ['DISABLED'], resource: 'chat/completions' },
  { apiStyle: 'OPENAI_CHAT_COMPLETIONS', compatibilityProfile: 'qwen-chat-v1', capabilityProfile: 'qwen-tools-v1', thinkingModes: ['DISABLED', 'ENABLED'], resource: 'chat/completions' },
  { apiStyle: 'OPENAI_CHAT_COMPLETIONS', compatibilityProfile: 'qwen-chat-v1', capabilityProfile: 'text-tools-v1', thinkingModes: ['DISABLED'], resource: 'chat/completions' },
  { apiStyle: 'DASHSCOPE_CHAT', compatibilityProfile: 'qwen-chat-v1', capabilityProfile: 'qwen-tools-v1', thinkingModes: ['DISABLED', 'ENABLED'], resource: 'services/aigc/text-generation/generation' },
  { apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1', capabilityProfile: 'responses-tools-v1', thinkingModes: ['DISABLED'], resource: 'responses' },
  { apiStyle: 'ANTHROPIC_MESSAGES', compatibilityProfile: 'anthropic-messages-v1', capabilityProfile: 'anthropic-tools-v1', thinkingModes: ['DISABLED'], resource: 'messages' },
];
const initial: ConnectionInput = { code: 'primary', name: 'Primary', provider: 'OPENAI', apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1', endpoint: 'https://api.openai.com/v1', enabled: true, expectedVersion: 7 };

describe('provider-specific model connections', () => {
  it('switches OpenAI to Qwen as a complete connection, preserving editable metadata and version', () => {
    const qwen = selectProvider(initial, 'QWEN', profiles);
    expect(qwen).toMatchObject({ code: 'primary', name: 'Primary', expectedVersion: 7, provider: 'QWEN', apiStyle: 'OPENAI_CHAT_COMPLETIONS', compatibilityProfile: 'qwen-chat-v1' });
    expect(connectionRequestUrl(qwen, profiles)).toBe('https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions');
    const openai = selectProvider(qwen, 'OPENAI', profiles);
    expect(openai).toMatchObject({ provider: 'OPENAI', apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1' });
    expect(connectionRequestUrl(openai, profiles)).toBe('https://api.openai.com/v1/responses');
  });

  it('switches both ways between Qwen protocols without reusing the wrong base path', () => {
    const qwen = selectProvider(initial, 'QWEN', profiles);
    const native = selectProviderApi(qwen, 'QWEN', profiles[3]);
    expect(connectionRequestUrl(native, profiles)).toBe('https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation');
    expect(connectionRequestUrl(selectProviderApi(native, 'QWEN', profiles[1]), profiles)).toBe('https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions');
  });

  it('preserves custom proxy prefixes when switching between OpenAI APIs', () => {
    const proxy = { ...initial, endpoint: 'https://proxy.example/tenant/ai/v1/' };
    const chat = selectProviderApi(proxy, 'OPENAI', profiles[0]);
    expect(connectionRequestUrl(chat, profiles)).toBe('https://proxy.example/tenant/ai/v1/chat/completions');
    expect(connectionRequestUrl(selectProviderApi(chat, 'OPENAI', profiles[4]), profiles)).toBe('https://proxy.example/tenant/ai/v1/responses');
  });

  it('recognizes older Qwen configurations labeled OPENAI and preserves custom providers', () => {
    expect(inferProvider({ provider: 'OPENAI', compatibilityProfile: 'qwen-chat-v1' })).toBe('QWEN');
    expect(inferProvider({ provider: 'DASHSCOPE', compatibilityProfile: 'qwen-chat-v1' })).toBe('QWEN');
    expect(inferProvider({ provider: 'ALIBABA_CLOUD', compatibilityProfile: 'qwen-chat-v1' })).toBe('QWEN');
    expect(inferProvider({ provider: 'Company gateway', compatibilityProfile: 'openai-chat-v1' })).toBe('CUSTOM');
    const custom = selectProvider(initial, 'CUSTOM', profiles);
    expect(custom).toEqual({ ...initial, provider: '' });
    expect(providerApis('CUSTOM', profiles)).toHaveLength(5);
  });

  it('uses the fixed native DashScope endpoint even from a custom provider', () => {
    const native = selectProviderApi(initial, 'CUSTOM', profiles[3]);
    expect(connectionRequestUrl(native, profiles)).toBe('https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation');
    expect(connectionRequestUrl(selectProviderApi(native, 'CUSTOM', profiles[5]), profiles)).toBe('https://api.anthropic.com/v1/messages');
  });

  it('only offers profiles advertised by the server', () => {
    expect(providerApis('QWEN', profiles.filter((p) => p.apiStyle !== 'DASHSCOPE_CHAT'))).toHaveLength(1);
    expect(providerApis('ANTHROPIC', [])).toEqual([]);
  });

  it.each([
    ['OPENAI', ['openai-chat-v1', 'openai-responses-v1']],
    ['QWEN', ['qwen-chat-v1', 'qwen-chat-v1']],
    ['ANTHROPIC', ['anthropic-messages-v1']],
  ] as const)('shows only %s APIs and never renders saved credentials in the edit form', (preset, compatibility) => {
    expect(providerApis(preset, profiles).map((p) => p.compatibilityProfile)).toEqual(compatibility);
    const markup = renderToStaticMarkup(createElement(ModelConnectionFields, {
      value: selectProvider(initial, preset, profiles), preset: preset as ProviderPreset, profiles, secret: '', modelId: 'fixture-model', onModelIdChange: () => {},
      editing: true, credentialStorageReady: true, onChange: () => {}, onProviderChange: () => {}, onSecretChange: () => {},
    }));
    const apiControls = markup.match(/<div role="group" aria-label="API"[^>]*>([\s\S]*?)<\/div>/)?.[1] ?? '';
    expect(apiControls.match(/aria-pressed=/g)).toHaveLength(compatibility.length);
    expect(markup).not.toContain('model_settings.code');
    expect(markup).toContain('model_settings.setup.model_id');
    if (preset === 'QWEN') {
      expect(markup).toContain('role="combobox"');
      expect(markup.match(/role="option"/g)).toHaveLength(4);
      for (const model of ['qwen3.8-max', 'qwen3.6-flash', 'qwen-plus', 'qwen3.5-plus']) expect(markup).toContain(`>${model}</span>`);
      expect(markup).toContain('value="fixture-model"');
      expect(markup).toContain('api_labels.QWEN_COMPATIBLE');
      expect(markup).toContain('api_labels.DASHSCOPE_CHAT');
      expect(markup).not.toContain('api_labels.OPENAI_RESPONSES');
    }
    if (preset !== 'QWEN') expect(markup).not.toContain('role="combobox"');
    if (preset === 'OPENAI') expect(markup).not.toContain('api_labels.DASHSCOPE_CHAT');
  });

  it.each(['http://example.test/v1', 'https://user:secret@example.test/v1', 'https://example.test/v1?token=private', 'https://example.test/v1#private'])('does not show unsafe request previews for %s', (endpoint) => {
    expect(connectionRequestUrl({ ...initial, endpoint }, profiles)).toBe('');
  });
});
