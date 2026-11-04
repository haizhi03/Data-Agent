import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ChatThinkingToggle } from './ChatThinkingToggle';
import type { ModelOption } from '../../types/ai';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
const model: ModelOption = { modelName: 'qwen', supportThinking: true, thinkingMode: 'DISABLED', thinkingModes: ['DISABLED', 'ENABLED'],
  memoryThreshold: null, maxContextTokens: null };

describe('thinking control in the chat composer', () => {
  it('only appears when the selected model advertises thinking support', () => {
    const render = (option?: ModelOption) => renderToStaticMarkup(createElement(ChatThinkingToggle, { model: option, onChange: vi.fn() }));
    expect(render(model)).toContain('aria-label="chat_thinking.label"');
    expect(render()).toBe('');
    expect(render({ ...model, supportThinking: false, thinkingModes: ['DISABLED'] })).toBe('');
    expect(render({ ...model, thinkingModes: undefined })).toBe('');
  });

  it('reflects the conversation state and lets a user enable or disable the next message', () => {
    const onChange = vi.fn();
    const off = ChatThinkingToggle({ model, value: 'DISABLED', onChange });
    expect(renderToStaticMarkup(off)).toContain('aria-pressed="false"');
    off?.props.onClick();expect(onChange).toHaveBeenLastCalledWith('ENABLED');
    const on = ChatThinkingToggle({ model, value: 'ENABLED', onChange });
    expect(renderToStaticMarkup(on)).toContain('aria-pressed="true"');
    expect(renderToStaticMarkup(on)).toContain('bg-primary/10');
    on?.props.onClick();expect(onChange).toHaveBeenLastCalledWith('DISABLED');
    const required = ChatThinkingToggle({ model: { ...model, thinkingMode: 'ENABLED', thinkingModes: ['ENABLED'] }, onChange });
    expect(renderToStaticMarkup(required)).toContain('disabled=""');
    onChange.mockClear();required?.props.onClick();expect(onChange).not.toHaveBeenCalled();
  });
});
