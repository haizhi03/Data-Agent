import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionTestPanel } from './ConnectionTestPanel';
import { connectionTestCode, connectionTestFailure } from '../../lib/connectionTest';
import type { ConnectionInput, ModelProfile } from '../../types/modelManagement';
import zh from '../../i18n/locales/zh.json';
import en from '../../i18n/locales/en.json';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
const value: ConnectionInput = { code: '', name: '', provider: 'OPENAI', apiStyle: 'OPENAI_RESPONSES',
  compatibilityProfile: 'openai-responses-v1', endpoint: 'https://example.com/v1', enabled: true };
const profiles: ModelProfile[] = [{ apiStyle: 'OPENAI_RESPONSES', compatibilityProfile: 'openai-responses-v1',
  capabilityProfile: 'responses-tools-v1', thinkingModes: ['DISABLED'], resource: 'responses' }];
function render(overrides: Partial<Parameters<typeof ConnectionTestPanel>[0]> = {}) {
  return renderToStaticMarkup(createElement(ConnectionTestPanel, {
    value, profiles, secret: '', hasCredential: false, disabled: false, testConnection: vi.fn(), ...overrides,
  }));
}

describe('test connection entry and safe feedback', () => {
  it('shows an explicit non-submit action and explains missing key/model requirements', () => {
    const markup = render();
    expect(markup).toContain('connection_test.action');expect(markup).toContain('connection_test.required');
    expect(markup).toMatch(/<button[^>]*type="button"[^>]*disabled=""/);
  });
  it('allows testing an unsaved draft before name and code are filled', () => {
    const markup = render({ secret: 'draft-fixture-key', initialModelId: 'fixture-model' });
    expect(markup).not.toContain('connection_test.required');expect(markup).not.toMatch(/<button[^>]* disabled=""/);
    expect(markup).not.toContain('draft-fixture-key');
  });
  it('can test a saved key without reading it back, and requires a valid HTTPS base URL', () => {
    expect(render({ connectionId: 'saved-fixture', hasCredential: true, initialModelId: 'fixture-model' })).not.toMatch(/<button[^>]* disabled=""/);
    expect(render({ hasCredential: true, initialModelId: 'fixture-model' })).toMatch(/<button[^>]* disabled=""/);
    expect(render({ secret: 'fixture', initialModelId: 'fixture-model', value: { ...value, endpoint: 'http://example.com/v1' } })).toMatch(/<button[^>]* disabled=""/);
  });
  it('keeps provider bodies and keys out of error messages', () => {
    expect(connectionTestFailure({ response: { data: { message: 'upstream secret fixture-key' } } }).code).toBe('MODEL_TEST_FAILED');
    expect(connectionTestCode('upstream secret fixture-key')).toBe('MODEL_TEST_FAILED');
    expect(connectionTestFailure({ response: { data: { message: 'MODEL_AUTH_FAILED' } } }).code).toBe('MODEL_AUTH_FAILED');
    expect(connectionTestFailure({ code: 'ECONNABORTED', message: 'request with fixture-key' }).code).toBe('MODEL_UPSTREAM_TIMEOUT');
    expect(connectionTestFailure({ code: 'ERR_NETWORK' }).code).toBe('MODEL_UPSTREAM_UNAVAILABLE');
    expect(connectionTestFailure(null).code).toBe('MODEL_TEST_FAILED');
  });
  it('provides Chinese and English feedback for every supported result code', () => {
    const codes = zh.model_settings.connection_test.codes;
    expect(Object.keys(codes).sort()).toEqual(Object.keys(en.model_settings.connection_test.codes).sort());
    for (const code of Object.keys(codes)) expect(connectionTestCode(code)).toBe(code);
  });
});
