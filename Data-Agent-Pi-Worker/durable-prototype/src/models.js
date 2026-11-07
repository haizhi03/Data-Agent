/**
 * Dynamic model registration for the durable prototype.
 *
 * Durable stores only a ModelRef ({provider, modelId}) in pi.agent; the actual
 * Model objects live in the host-owned Models registry. One provider per
 * modelId (stable id `dal:<modelId>`), rebuilt on every registration, so auth
 * resolution is scoped to exactly one model.
 *
 * Credentials never touch durable storage. The provider's apiKey auth RESOLVES
 * THROUGH A GATE: if the key is not in process memory yet (typical after a
 * crash, before the host re-sends start), resolve() waits until a start
 * re-registers it. This is what keeps a recovered run's model retry — durable
 * retries a killed request on a fixed backoff (~2s) — from failing no_model
 * while the host is still re-attaching. The gate is bounded by
 * DURABLE_MODEL_GATE_MS (default 120s): once it expires, resolve() reports
 * unconfigured and the run fails no_model, preserving the restart-limit /
 * retry semantics as the final arbiter of a run nobody re-attaches to.
 */
import { createModels, createProvider } from '@earendil-works/pi-ai/models';
import { openAICompletionsApi } from '@earendil-works/pi-ai/api/openai-completions.lazy';
import { anthropicMessagesApi } from '@earendil-works/pi-ai/api/anthropic-messages.lazy';

export const SUPPORTED_API_STYLES = new Set(['OPENAI_CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES']);

export class UnsupportedApiStyleError extends Error {
  constructor(style) {
    super(`Pi worker does not support apiStyle ${style}`);
    this.code = 'PI_UNSUPPORTED_API_STYLE';
  }
}

function positiveInt(value, fallback) {
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

function validateModelConfig(model) {
  if (!model || typeof model !== 'object') throw new UnsupportedApiStyleError('<missing>');
  if (!SUPPORTED_API_STYLES.has(model.apiStyle)) throw new UnsupportedApiStyleError(String(model.apiStyle));
  if (typeof model.baseUrl !== 'string' || !/^https?:\/\//.test(model.baseUrl)) {
    throw new Error('model.baseUrl must be an http(s) URL');
  }
  if (typeof model.modelId !== 'string' || !model.modelId.trim()) throw new Error('model.modelId is required');
}

export function providerIdFor(modelId) {
  return `dal:${modelId}`;
}

export class ModelRegistry {
  constructor({ gateMs = 120_000 } = {}) {
    this.models = createModels();
    /** @type {Map<string, {config: object, apiKey: string|null, waiters: Array<(key: string|null) => void>}>} */
    this.entries = new Map();
    this.gateMs = gateMs;
  }

  /**
   * Register (or replace) a model with its credential — the start path.
   * @returns {{provider: string, modelId: string}} the ModelRef to configure on the conversation
   */
  register(model, apiKey) {
    validateModelConfig(model);
    const entry = this.#ensure(model);
    entry.apiKey = apiKey;
    for (const waiter of entry.waiters.splice(0)) waiter(apiKey);
    return { provider: providerIdFor(model.modelId), modelId: model.modelId };
  }

  /**
   * Register a model WITHOUT a credential — the recovery path, from the
   * non-secret model config stored in the journal. The provider resolves, so
   * a recovered run's retry does not fail no_model; its auth resolve gates
   * until register() supplies the key.
   */
  registerShell(model) {
    validateModelConfig(model);
    this.#ensure(model);
    return { provider: providerIdFor(model.modelId), modelId: model.modelId };
  }

  hasCredential(modelId) {
    return typeof this.entries.get(modelId)?.apiKey === 'string';
  }

  #ensure(config) {
    let entry = this.entries.get(config.modelId);
    if (!entry) {
      entry = { config, apiKey: null, waiters: [] };
      this.entries.set(config.modelId, entry);
      this.#upsertProvider(entry);
    }
    return entry;
  }

  #upsertProvider(entry) {
    const config = entry.config;
    const anthropic = config.apiStyle === 'ANTHROPIC_MESSAGES';
    const model = {
      id: config.modelId,
      name: config.modelId,
      api: anthropic ? 'anthropic-messages' : 'openai-completions',
      provider: providerIdFor(config.modelId),
      baseUrl: config.baseUrl,
      reasoning: false,
      input: ['text'],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      contextWindow: positiveInt(config.contextWindow, anthropic ? 200000 : 128000),
      maxTokens: positiveInt(config.maxOutputTokens, 8192),
      // OpenAI-compatible gateways do not reliably accept these fields.
      ...(anthropic ? {} : { compat: { supportsDeveloperRole: false, supportsReasoningEffort: false } }),
    };
    this.models.setProvider(createProvider({
      id: providerIdFor(config.modelId),
      name: `Data Agent Local (${config.modelId})`,
      baseUrl: config.baseUrl,
      auth: {
        apiKey: {
          name: 'per-start API key',
          resolve: ({ signal } = {}) => this.#resolveKey(entry, signal),
        },
      },
      models: [model],
      api: anthropic
        ? { 'anthropic-messages': anthropicMessagesApi() }
        : { 'openai-completions': openAICompletionsApi() },
    }));
  }

  /**
   * Auth resolution, called by Models.getAuth on every request attempt.
   * Resolves immediately when the key is known; otherwise gates.
   */
  #resolveKey(entry, signal) {
    if (entry.apiKey) return Promise.resolve({ auth: { apiKey: entry.apiKey } });
    return new Promise((resolve) => {
      let settled = false;
      const finish = (key) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        signal?.removeEventListener('abort', onAbort);
        // key === null (gate expired or aborted): report unconfigured, so the
        // run fails no_model instead of hanging forever.
        resolve(key ? { auth: { apiKey: key } } : undefined);
      };
      const onAbort = () => finish(null);
      const timer = setTimeout(() => finish(null), this.gateMs);
      timer.unref?.();
      signal?.addEventListener('abort', onAbort, { once: true });
      entry.waiters.push(finish);
    });
  }
}
