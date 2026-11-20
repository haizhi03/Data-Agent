import type { ConnectionInput, ManagedModel, ModelConnection, ModelOptions, ModelProfile, ModelSetupInput, PurposeBinding, ThinkingMode } from '../types/modelManagement';

/** Defaults come from the same profile that validates requests on the server. */
export function profileOptions(profile?: ModelProfile, mode: ThinkingMode = profile?.thinkingModes[0] ?? 'DISABLED', maxOutputTokens: number | null = null): ModelOptions {
  const rules = profile?.optionRules;
  return { thinkingMode: mode, maxOutputTokens,
    ...(mode === 'ENABLED' && rules?.defaultReasoningEffort ? { reasoningEffort: rules.defaultReasoningEffort } : {}),
    ...(mode === 'ENABLED' && rules?.thinkingBudgetRequired ? { thinkingBudget: rules.minThinkingBudget } : {}),
  };
}

export function setupFromModel(profiles: ModelProfile[], connection?: ModelConnection, model?: ManagedModel): ModelSetupInput {
  const apiStyle = connection?.apiStyle ?? 'OPENAI_RESPONSES';
  const compatibilityProfile = connection?.compatibilityProfile ?? 'openai-responses-v1';
  const profile = profiles.find((p) => p.apiStyle === apiStyle && p.compatibilityProfile === compatibilityProfile);
  return {
    ...(model ? { modelConfigId: model.id, modelExpectedVersion: model.version } : {}),
    ...(connection ? { connectionId: connection.id, connectionExpectedVersion: connection.version } : {}),
    name: model?.name ?? connection?.name ?? '', provider: connection?.provider ?? 'OPENAI', apiStyle, compatibilityProfile,
    endpoint: connection?.endpoint ?? 'https://api.openai.com/v1', modelId: model?.modelId ?? '',
    capabilityProfile: model?.capabilityProfile ?? profile?.capabilityProfile ?? '',
    defaultOptions: model ? { ...model.defaultOptions, ...(model.tokenLimits ? { maxOutputTokens: model.tokenLimits.maxOutputTokens ?? null } : {}) } : profileOptions(profile),
    maxContextTokens: model?.tokenLimits ? model.tokenLimits.maxContextTokens ?? null : model?.maxContextTokens ?? null,
    memoryThreshold: model?.memoryThreshold ?? null,
    chatVisible: model?.chatVisible ?? true, enabled: (model?.enabled ?? true) && (connection?.enabled ?? true), makeDefault: false,
  };
}

export function setupConnection(setup: ModelSetupInput): ConnectionInput {
  return { code: '', name: setup.name, provider: setup.provider, apiStyle: setup.apiStyle, endpoint: setup.endpoint,
    compatibilityProfile: setup.compatibilityProfile, enabled: true, expectedVersion: setup.connectionExpectedVersion };
}

export function changeSetupConnection(setup: ModelSetupInput, connection: ConnectionInput, profiles: ModelProfile[]): ModelSetupInput {
  const changed = setup.apiStyle !== connection.apiStyle || setup.compatibilityProfile !== connection.compatibilityProfile;
  const profile = profiles.find((p) => p.apiStyle === connection.apiStyle && p.compatibilityProfile === connection.compatibilityProfile);
  return { ...setup, name: connection.name, provider: connection.provider, apiStyle: connection.apiStyle, endpoint: connection.endpoint,
    compatibilityProfile: connection.compatibilityProfile,
    ...(changed ? { capabilityProfile: profile?.capabilityProfile ?? '', defaultOptions: profileOptions(profile, undefined, setup.defaultOptions.maxOutputTokens ?? null) } : {}),
  };
}

export function setupSaveBody(setup: ModelSetupInput, secret: string, bindings: PurposeBinding[]): ModelSetupInput {
  const body = { ...setup };delete body.apiKey;delete body.bindingVersions;
  return { ...body, ...(secret ? { apiKey: secret } : {}),
    ...(setup.makeDefault ? { bindingVersions: Object.fromEntries(bindings.map((b) => [b.purpose, b.version])) } : {}),
  };
}
