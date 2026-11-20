package edu.zsc.ai.config.ai.model;

import java.util.List;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode;

/** Shared by configuration validation, request defaults, probes and the settings form. */
public record ModelOptionRules(List<String> reasoningEfforts, String defaultReasoningEffort,
                               Integer minThinkingBudget, boolean thinkingBudgetRequired,
                               List<ThinkingMode> temperatureModes, double maxTemperature,
                               boolean preserveThinking, Integer defaultMaxOutputTokens,
                               int testMaxOutputTokens, int minimumThinkingOutputTokens) {
    public ModelOptionRules {
        reasoningEfforts = List.copyOf(reasoningEfforts);
        temperatureModes = List.copyOf(temperatureModes);
    }
}
