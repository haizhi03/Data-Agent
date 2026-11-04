import type { ApiStyle, ConnectionInput, ModelProfile } from '../types/modelManagement';

export type ProviderPreset = 'OPENAI' | 'QWEN' | 'ANTHROPIC' | 'CUSTOM';
export const providerPresets: ProviderPreset[] = ['OPENAI', 'QWEN', 'ANTHROPIC', 'CUSTOM'];
const presetProfiles = {
  OPENAI: ['openai-chat-v1', 'openai-responses-v1'],
  QWEN: ['qwen-chat-v1'],
  ANTHROPIC: ['anthropic-messages-v1'],
};

export function inferProvider(connection: Pick<ConnectionInput, 'provider' | 'compatibilityProfile'>): ProviderPreset {
  const provider = connection.provider.trim().toUpperCase();
  if (!['OPENAI', 'QWEN', 'DASHSCOPE', 'ALIBABA_CLOUD', 'ANTHROPIC', 'CLAUDE'].includes(provider)) return 'CUSTOM';
  // Earlier forms defaulted provider to OPENAI even when a Qwen profile was selected.
  if (connection.compatibilityProfile === 'qwen-chat-v1') return 'QWEN';
  if (connection.compatibilityProfile === 'anthropic-messages-v1') return 'ANTHROPIC';
  if (presetProfiles.OPENAI.includes(connection.compatibilityProfile)) return 'OPENAI';
  return 'CUSTOM';
}

export function providerApis(provider: ProviderPreset, profiles: ModelProfile[]): ModelProfile[] {
  return profiles.filter((p, index, all) =>
    (provider === 'CUSTOM' || presetProfiles[provider].includes(p.compatibilityProfile))
    && all.findIndex((other) => other.apiStyle === p.apiStyle && other.compatibilityProfile === p.compatibilityProfile) === index);
}

function defaultEndpoint(provider: ProviderPreset, style: ApiStyle): string {
  if (provider === 'QWEN') return style === 'DASHSCOPE_CHAT'
    ? 'https://dashscope.aliyuncs.com/api/v1' : 'https://dashscope.aliyuncs.com/compatible-mode/v1';
  if (provider === 'ANTHROPIC') return 'https://api.anthropic.com/v1';
  return 'https://api.openai.com/v1';
}

export function selectProvider(connection: ConnectionInput, provider: ProviderPreset, profiles: ModelProfile[]): ConnectionInput {
  if (provider === 'CUSTOM') return { ...connection, provider: '' };
  const available = providerApis(provider, profiles);
  const profile = (provider === 'OPENAI' ? available.find((p) => p.apiStyle === 'OPENAI_RESPONSES') : undefined) ?? available[0];
  if (!profile) return connection;
  return { ...connection, provider, apiStyle: profile.apiStyle, compatibilityProfile: profile.compatibilityProfile, endpoint: defaultEndpoint(provider, profile.apiStyle) };
}

export function selectProviderApi(connection: ConnectionInput, provider: ProviderPreset, profile: ModelProfile): ConnectionInput {
  const endpointProvider = profile.compatibilityProfile === 'qwen-chat-v1' ? 'QWEN'
    : profile.compatibilityProfile === 'anthropic-messages-v1' ? 'ANTHROPIC' : 'OPENAI';
  return { ...connection, apiStyle: profile.apiStyle, compatibilityProfile: profile.compatibilityProfile,
    // Native DashScope and the compatible API use different roots. OpenAI's APIs share a root,
    // so preserve user-supplied proxy prefixes when switching Chat Completions / Responses.
    endpoint: profile.apiStyle === 'DASHSCOPE_CHAT' || connection.apiStyle === 'DASHSCOPE_CHAT'
      || (provider === 'QWEN' && connection.apiStyle !== profile.apiStyle)
      ? defaultEndpoint(endpointProvider, profile.apiStyle) : connection.endpoint };
}

export function connectionRequestUrl(connection: ConnectionInput, profiles: ModelProfile[]): string {
  try {
    const profile = profiles.find((p) => p.apiStyle === connection.apiStyle && p.compatibilityProfile === connection.compatibilityProfile);
    if (!profile) return '';
    const base = new URL(connection.endpoint.endsWith('/') ? connection.endpoint : `${connection.endpoint}/`);
    if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) return '';
    return new URL(profile.resource, base).toString();
  } catch { return ''; }
}
