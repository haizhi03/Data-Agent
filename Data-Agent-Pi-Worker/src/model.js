/**
 * Per-run immutable model endpoint construction.
 *
 * The Java host resolves the configured model and credential for each run and
 * passes a secret-free description plus the API key inside the start message
 * (stdin pipe only, never command line or logs). This worker keeps no model
 * configuration or credential store of its own.
 *
 * Verified live: OPENAI_CHAT_COMPLETIONS against an OpenAI-compatible endpoint.
 * ANTHROPIC_MESSAGES is wired through the same pi-ai adapter but has not been
 * verified against a live endpoint in this project yet.
 */
import { openAICompletionsApi } from '@earendil-works/pi-ai/api/openai-completions.lazy';
import { anthropicMessagesApi } from '@earendil-works/pi-ai/api/anthropic-messages.lazy';

export const SUPPORTED_API_STYLES = new Set(['OPENAI_CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES']);

const PROVIDER_ID = 'data-agent-local';

export class UnsupportedApiStyleError extends Error {
  constructor(style) {
    super(`Pi worker does not support apiStyle ${style}`);
    this.code = 'PI_UNSUPPORTED_API_STYLE';
  }
}

/**
 * @param {{apiStyle: string, baseUrl: string, modelId: string,
 *          contextWindow?: number, maxOutputTokens?: number}} config
 * @returns {{model: object, streams: object}}
 */
export function buildModelEndpoint(config) {
  if (!config || typeof config !== 'object') throw new UnsupportedApiStyleError('<missing>');
  const style = config.apiStyle;
  if (!SUPPORTED_API_STYLES.has(style)) throw new UnsupportedApiStyleError(String(style));
  if (typeof config.baseUrl !== 'string' || !/^https?:\/\//.test(config.baseUrl)) {
    throw new Error('model.baseUrl must be an http(s) URL');
  }
  if (typeof config.modelId !== 'string' || !config.modelId.trim()) {
    throw new Error('model.modelId is required');
  }
  if (style === 'OPENAI_CHAT_COMPLETIONS') {
    return {
      model: {
        id: config.modelId,
        name: config.modelId,
        api: 'openai-completions',
        provider: PROVIDER_ID,
        baseUrl: config.baseUrl,
        reasoning: false,
        input: ['text'],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
        contextWindow: positiveInt(config.contextWindow, 128000),
        maxTokens: positiveInt(config.maxOutputTokens, 8192),
        // OpenAI-compatible gateways (e.g. DashScope compatible mode) do not
        // reliably accept the `developer` role or reasoning_effort fields.
        compat: { supportsDeveloperRole: false, supportsReasoningEffort: false },
      },
      streams: openAICompletionsApi(),
    };
  }
  return {
    model: {
      id: config.modelId,
      name: config.modelId,
      api: 'anthropic-messages',
      provider: PROVIDER_ID,
      baseUrl: config.baseUrl,
      reasoning: false,
      input: ['text'],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      contextWindow: positiveInt(config.contextWindow, 200000),
      maxTokens: positiveInt(config.maxOutputTokens, 8192),
    },
    streams: anthropicMessagesApi(),
  };
}

function positiveInt(value, fallback) {
  return Number.isInteger(value) && value > 0 ? value : fallback;
}
