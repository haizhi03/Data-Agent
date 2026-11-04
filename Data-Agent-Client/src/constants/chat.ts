import { I18N_KEYS } from './i18nKeys';

/**
 * API paths and error/label constants for chat and AI assistant.
 */

/** Default chat stream API path. */
export const CHAT_STREAM_API = '/api/chat/stream';

/** Thrown when no valid access token. */
export const NOT_AUTHENTICATED = 'Not authenticated';

/** Shown when session expired and user must login again. */
export const SESSION_EXPIRED_MESSAGE = 'Session expired, please login again';

/** ThoughtBlock label while streaming (thinking). */
export const THOUGHT_LABEL_THINKING = 'Thinking';

/** ThoughtBlock label when thought is done. */
export const THOUGHT_LABEL_THOUGHT = 'Thought';

/** ToolRunBlock: prefix when tool execution failed. */
export const TOOL_RUN_LABEL_FAILED = 'Failed ';

/** ToolRunBlock: prefix when tool ran successfully. */
export const TOOL_RUN_LABEL_RAN = 'Ran ';

/** ToolRunBlock: section title for parameters. */
export const TOOL_RUN_SECTION_PARAMETERS = 'PARAMETERS';

/** ToolRunBlock: section title for response. */
export const TOOL_RUN_SECTION_RESPONSE = 'RESPONSE';

/** Placeholder when parameters or response is empty. */
export const TOOL_RUN_EMPTY_PLACEHOLDER = '—';

/** Status key → i18n key mapping for STATUS blocks. */
export const STATUS_LABEL_KEYS: Record<string, string> = {
  MODEL_CONFIG_UNAVAILABLE: 'ai.model_errors.unavailable',
  MODEL_MANAGEMENT_UNAVAILABLE: 'ai.model_errors.unavailable',
  MODEL_CAPABILITY_UNSUPPORTED: 'ai.model_errors.capability',
  MODEL_AUTH_FAILED: 'ai.model_errors.auth',
  MODEL_RATE_LIMITED: 'ai.model_errors.rate_limit',
  MODEL_UPSTREAM_TIMEOUT: 'ai.model_errors.timeout',
  MODEL_UPSTREAM_UNAVAILABLE: 'ai.model_errors.upstream',
  MODEL_PROTOCOL_ERROR: 'ai.model_errors.protocol',
  MODEL_PROVIDER_STATE_INCOMPATIBLE: 'ai.model_errors.history',
  MODEL_OUTPUT_TRUNCATED: 'ai.model_errors.truncated',
  MODEL_REFUSED: 'ai.model_errors.refused',
  AGENT_BUDGET_EXCEEDED: 'ai.model_errors.budget',
  AGENT_RUN_CANCELLED: 'ai.model_errors.cancelled',
  TOOL_FORBIDDEN: 'ai.model_errors.tool',
  TOOL_VALIDATION_FAILED: 'ai.model_errors.tool',
  compacting: I18N_KEYS.AI.COMPACT.LOADING,
  compressing: I18N_KEYS.AI.COMPACT.LOADING,
};

/** SubAgent type → display label mapping. */
export const SUB_AGENT_LABELS: Record<string, string> = {
  explorer: 'Explorer',
  EXPLORER: 'Explorer',
  planner: 'SQL Planner',
  PLANNER: 'SQL Planner',
};

/** SubAgent phase → display label mapping. */
export const SUB_AGENT_PHASE_LABELS: Record<string, string> = {
  start: 'Starting...',
  progress: 'Processing...',
  complete: 'Completed',
  error: 'Failed',
};
