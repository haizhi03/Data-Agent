import type { ConnectionTestResult } from '../types/modelManagement';

const resultCodes = ['MODEL_TEST_OK', 'MODEL_AUTH_FAILED', 'MODEL_RATE_LIMITED', 'MODEL_UPSTREAM_TIMEOUT', 'MODEL_UPSTREAM_UNAVAILABLE',
  'MODEL_PROTOCOL_ERROR', 'MODEL_OUTPUT_TRUNCATED', 'MODEL_REFUSED', 'MODEL_CAPABILITY_UNSUPPORTED', 'MODEL_CONFIG_CONFLICT',
  'MODEL_CONFIG_NOT_FOUND', 'MODEL_CONFIG_INVALID', 'MODEL_MANAGEMENT_FORBIDDEN', 'MODEL_MANAGEMENT_UNAVAILABLE',
  'MODEL_TEST_BUSY', 'MODEL_TEST_INTERRUPTED', 'MODEL_TEST_FAILED', 'MODEL_TEST_RATE_LIMITED', 'MODEL_TEST_CREDENTIAL_REQUIRED',
  'MODEL_TEST_API_INVALID', 'MODEL_TEST_ENDPOINT_INVALID', 'MODEL_TEST_DASHSCOPE_ENDPOINT_INVALID', 'MODEL_TEST_MODEL_ID_INVALID',
  'MODEL_TEST_KEY_INVALID', 'MODEL_TEST_PROVIDER_INVALID', 'MODEL_TEST_VERSION_INVALID'] as const;
type ConnectionTestCode = typeof resultCodes[number];
export function connectionTestCode(code: unknown): ConnectionTestCode {
  return resultCodes.find((known) => known === code) ?? 'MODEL_TEST_FAILED';
}
export function connectionTestFailure(error: unknown): ConnectionTestResult {
  const value = error as { response?: { data?: { message?: unknown } }; code?: unknown; message?: unknown };
  const code = value?.code === 'ECONNABORTED' || value?.code === 'ETIMEDOUT' ? 'MODEL_UPSTREAM_TIMEOUT'
    : value?.code === 'ERR_NETWORK' ? 'MODEL_UPSTREAM_UNAVAILABLE'
    : connectionTestCode(value?.response?.data?.message ?? value?.message);
  return { success: false, code, elapsedMs: 0 };
}
