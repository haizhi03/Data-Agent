import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ChatInputToolbar } from './ChatInputToolbar';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
describe('model configuration entry', () => {
  it('shows a visible configuration action when no callable model exists', () => {
    const markup = renderToStaticMarkup(createElement(MemoryRouter, null, createElement(ChatInputToolbar, {
      agent: 'Agent', setAgent: () => {}, model: '', setModel: () => {}, modelOptions: [], onSend: () => {}, agents: [], CurrentAgentIcon: () => null,
    })));
    expect(markup).toContain('model_settings.configure');
    expect(markup).toMatch(/disabled=""[^>]*aria-label="Send"/);
  });
});
