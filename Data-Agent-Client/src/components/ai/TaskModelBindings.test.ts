import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { TaskModelBindings } from './TaskModelBindings';
import type { ManagedModel, ModelConnection, PurposeBinding } from '../../types/modelManagement';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
describe('task model selectors', () => {
  it('renders four Qwen choices in each of the five task selectors while retaining the selected configuration', () => {
    const connection: ModelConnection = { id: 'one', code: 'qwen', name: 'Qwen', provider: 'QWEN', apiStyle: 'OPENAI_CHAT_COMPLETIONS',
      endpoint: 'https://example.com/v1', requestUrl: '', compatibilityProfile: 'qwen-chat-v1', authType: 'API_KEY', hasCredential: true,
      credentialKind: 'ENCRYPTED', credentialVersion: 1, version: 2, enabled: true };
    const model: ManagedModel = { id: 'configured', code: 'qwen', name: 'Qwen', connectionId: connection.id, modelId: 'qwen3.8-max',
      capabilityProfile: 'qwen-tools-v1', defaultOptions: { thinkingMode: 'DISABLED' }, maxContextTokens: 1000000, memoryThreshold: 24000,
      chatVisible: true, enabled: true, available: true, version: 1 };
    const bindings: PurposeBinding[] = ['MAIN_CHAT', 'EXPLORER', 'PLANNER', 'COMPRESSION', 'MEMORY_WRITER'].map((purpose) => ({ purpose: purpose as PurposeBinding['purpose'], modelConfigId: model.id, version: 1 }));
    const markup = renderToStaticMarkup(createElement(TaskModelBindings, { bindings, connections: [connection], models: [model], specifications: [], busy: false, onSelect: vi.fn() }));
    expect(markup.match(/<select/g)).toHaveLength(5);expect(markup.match(/selected=""/g)).toHaveLength(5);
    expect(markup.match(/value="preset\/one\//g)).toHaveLength(15);
    for (const id of ['qwen3.8-max', 'qwen3.6-flash', 'qwen-plus', 'qwen3.5-plus']) expect(markup.match(new RegExp(`Qwen · ${id.replace(/\./g, '\\.')}`, 'g'))).toHaveLength(5);
    expect(markup).not.toContain('model_settings.unavailable');
    expect(markup).not.toContain('type="checkbox"');
  });
});
