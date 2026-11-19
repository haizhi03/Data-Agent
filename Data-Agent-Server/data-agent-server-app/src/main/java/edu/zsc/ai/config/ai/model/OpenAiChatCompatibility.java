package edu.zsc.ai.config.ai.model;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import edu.zsc.ai.domain.service.ai.model.ModelProfiles;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.ENABLED;

/** Wire differences for providers sharing Chat Completions; no extra client factory is required. */
enum OpenAiChatCompatibility {
    STANDARD("openai-chat-v1") {
        void configure(OpenAiChatRequestParameters.Builder builder, ResolvedModelConfig config) {}
    },
    QWEN("qwen-chat-v1") {
        void configure(OpenAiChatRequestParameters.Builder builder, ResolvedModelConfig config) {
            var options = config.options();
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("enable_thinking", options.thinkingMode() == ENABLED);
            if (options.thinkingMode() == ENABLED && options.thinkingBudget() != null) parameters.put("thinking_budget", options.thinkingBudget());
            builder.customParameters(parameters);
        }
    },
    DEEPSEEK("deepseek-chat-v1") {
        void configure(OpenAiChatRequestParameters.Builder builder, ResolvedModelConfig config) {
            var options = config.options();
            boolean thinking = options.thinkingMode() == ENABLED;
            // The switch is always sent: the provider thinks by default, so omitting it would make "off" a no-op.
            // Temperature and sampling penalties are ignored while thinking, so only non-thinking requests carry one.
            builder.temperature(thinking ? null : options.temperature())
                    .reasoningEffort(thinking ? Objects.requireNonNullElse(options.reasoningEffort(), rules(config).defaultReasoningEffort()) : null)
                    .customParameters(Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled")));
        }
    };

    private final String profile;
    OpenAiChatCompatibility(String profile) { this.profile = profile; }
    abstract void configure(OpenAiChatRequestParameters.Builder builder, ResolvedModelConfig config);
    static OpenAiChatCompatibility forConfig(ResolvedModelConfig config) {
        for (var strategy : values()) if (strategy.profile.equals(config.compatibilityProfile())) return strategy;
        throw ModelIntegrationException.capability();
    }
    static ModelOptionRules rules(ResolvedModelConfig config) {
        var profile = ModelProfiles.find(config.apiStyle(), config.compatibilityProfile(), config.capabilityProfile());
        if (profile == null) throw ModelIntegrationException.capability();
        return profile.optionRules();
    }
}
