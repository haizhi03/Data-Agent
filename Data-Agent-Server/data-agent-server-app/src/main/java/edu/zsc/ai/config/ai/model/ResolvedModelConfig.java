package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode;
import java.net.URI;
import java.util.Set;

/** Immutable, secret-free snapshot. Startup IDs are scoped codes; M3 replaces them with DB IDs. */
public record ResolvedModelConfig(
        String modelConfigId, long configVersion, String connectionId, long connectionVersion,
        ModelAccessContext access, String provider, ModelApiStyle apiStyle, String modelId, String name,
        URI endpoint, String credentialRef, long credentialVersion,
        String capabilityProfile, String compatibilityProfile, EffectiveOptions options,
        int maxContextTokens, int memoryThreshold, boolean chatVisible, Set<String> allowedPrivateHosts) {
    public ResolvedModelConfig { allowedPrivateHosts = Set.copyOf(allowedPrivateHosts); }
    public record EffectiveOptions(ThinkingMode thinkingMode, Integer thinkingBudget,
                                   Double temperature, Integer maxOutputTokens, String reasoningEffort) {
        public EffectiveOptions(ThinkingMode mode, Integer budget, Double temperature, Integer output) {
            this(mode, budget, temperature, output, null);
        }
    }
}
