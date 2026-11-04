import { createElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatModelSelector } from './ChatModelSelector';
import type { ModelOption } from '../../types/ai';

const handlers = vi.hoisted(() => ({ groups: [] as Array<{ value: string; onValueChange: (value: string) => void }>, configure: undefined as (() => void) | undefined }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
// Keep closed popup contents mounted so we can verify menu data and callbacks without a DOM.
vi.mock('../ui/DropdownMenu', () => {
  const container = ({ children }: { children?: ReactNode }) => createElement('div', null, children);
  return {
    DropdownMenu: container, DropdownMenuContent: container, DropdownMenuLabel: container,
    DropdownMenuPortal: container, DropdownMenuSub: container, DropdownMenuSubContent: container,
    DropdownMenuSubTrigger: container, DropdownMenuTrigger: container, DropdownMenuSeparator: container,
    DropdownMenuRadioGroup: (props: { value: string; onValueChange: (value: string) => void; children?: ReactNode }) => {
      handlers.groups.push(props);
      return createElement('div', { 'data-selected': props.value }, props.children);
    },
    DropdownMenuRadioItem: ({ value, children }: { value: string; children?: ReactNode }) => createElement('button', { 'data-model': value }, children),
    DropdownMenuItem: ({ onSelect, children }: { onSelect?: () => void; children?: ReactNode }) => {
      handlers.configure = onSelect;
      return createElement('div', null, children);
    },
  };
});

const options: ModelOption[] = [
  { modelName: 'qwen-config', modelConfigId: 'qwen-config', modelId: 'qwen3.8-max', name: 'qwen', provider: 'QWEN', supportThinking: true, memoryThreshold: 900000, maxContextTokens: 1000000 },
  { modelName: 'openai-config', modelConfigId: 'openai-config', modelId: 'account-model', name: 'OpenAI 主连接', provider: 'OPENAI', supportThinking: false, memoryThreshold: 24000, maxContextTokens: 32768 },
];

describe('chat model selector', () => {
  beforeEach(() => { handlers.groups = [];handlers.configure = undefined; });

  it('shows provider submenus, actual model IDs and the selected model, while selecting by configuration ID', () => {
    const select = vi.fn();const configure = vi.fn();
    const markup = renderToStaticMarkup(createElement(ChatModelSelector, { value: 'qwen-config', options, onSelect: select, onConfigure: configure }));
    expect(markup).toContain('model_settings.providers.QWEN');
    expect(markup).toContain('model_settings.providers.OPENAI');
    expect(markup).toContain('chat_model_selector.title');
    expect(markup).toContain('chat_model_selector.choose: qwen3.8-max');
    expect(markup).not.toContain('>qwen</span>');
    expect(markup.match(/aria-label="chat_model_selector.selected"/g)).toHaveLength(1);
    expect(markup).toContain('data-model="qwen-config"');
    expect(handlers.groups.map((group) => group.value)).toEqual(['qwen-config', 'qwen-config']);
    handlers.groups[1].onValueChange('openai-config');
    expect(select).toHaveBeenCalledWith('openai-config');
    handlers.configure?.();
    expect(configure).toHaveBeenCalledOnce();
  });

  it('retains a configuration action with an empty workspace or a removed selection', () => {
    for (const available of [[], options]) {
      const configure = vi.fn();
      const markup = renderToStaticMarkup(createElement(ChatModelSelector, { value: 'removed', options: available, onSelect: vi.fn(), onConfigure: configure }));
      expect(markup).toContain('model_settings.configure');
      expect(markup).toContain('ai.no_available_models');
      expect(markup).not.toContain('aria-label="chat_model_selector.selected"');
      handlers.configure?.();
      expect(configure).toHaveBeenCalledOnce();
    }
  });

  it('shows four Qwen conversation choices from one configured connection and switches to the chosen model', () => {
    const models = ['qwen3.8-max', 'qwen3.6-flash', 'qwen-plus', 'qwen3.5-plus'];
    const choices = models.map((modelId, index) => ({ ...options[0], modelId,
      modelName: index ? `qwen-preset/qwen-config/${modelId}` : 'qwen-config',
      modelConfigId: index ? `qwen-preset/qwen-config/${modelId}` : 'qwen-config' }));
    const select = vi.fn();
    const markup = renderToStaticMarkup(createElement(ChatModelSelector, { value: choices[3].modelName, options: choices, onSelect: select, onConfigure: vi.fn() }));
    expect(markup.match(/model_settings.providers.QWEN/g)).toHaveLength(1);
    expect(markup.match(/data-model=/g)).toHaveLength(4);
    for (const model of models) expect(markup).toContain(`>${model}</span>`);
    expect(markup).not.toContain('>qwen</span>');
    expect(markup).toContain('chat_model_selector.choose: qwen3.5-plus');
    expect(handlers.groups).toHaveLength(1);
    handlers.groups[0].onValueChange(choices[2].modelName);
    expect(select).toHaveBeenCalledWith('qwen-preset/qwen-config/qwen-plus');
  });
});
