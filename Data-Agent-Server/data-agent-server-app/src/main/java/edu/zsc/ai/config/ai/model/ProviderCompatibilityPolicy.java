package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.domain.service.ai.model.ModelProfiles;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.*;

/** Validates against the same capability definitions served to the settings form. */
public final class ProviderCompatibilityPolicy {
    private ProviderCompatibilityPolicy() {}

    public static void validate(Model model, Connection connection) {
        var profile = ModelProfiles.find(connection.getApiStyle(), connection.getCompatibilityProfile(), model.getCapabilityProfile());
        if (profile == null) throw new IllegalStateException("Unsupported model protocol or capability/compatibility profile");
        var rules = profile.optionRules();
        Options o = model.getDefaultOptions();
        Integer output = effectiveOutputLimit(model, connection);
        if (o == null || o.getThinkingMode() == null || !profile.thinkingModes().contains(o.getThinkingMode())
                || (o.getThinkingBudget() != null && (rules.minThinkingBudget() == null
                    || o.getThinkingMode() != ThinkingMode.ENABLED || o.getThinkingBudget() < rules.minThinkingBudget()
                    || o.getThinkingBudget() >= model.getMaxContextTokens()
                    || (output != null && (rules.thinkingBudgetRequired() ? o.getThinkingBudget() >= output : o.getThinkingBudget() > output))))
                || (rules.thinkingBudgetRequired() && o.getThinkingMode() == ThinkingMode.ENABLED && o.getThinkingBudget() == null)
                || (o.getTemperature() != null && (!Double.isFinite(o.getTemperature()) || o.getTemperature() < 0
                    || o.getTemperature() > rules.maxTemperature() || !rules.temperatureModes().contains(o.getThinkingMode())))
                || (output != null && (output < 1 || output >= model.getMaxContextTokens()))
                || (o.getReasoningEffort() != null && (o.getThinkingMode() != ThinkingMode.ENABLED
                    || !rules.reasoningEfforts().contains(o.getReasoningEffort())))) {
            throw new IllegalStateException("Unsupported model invocation options");
        }
        if (connection.getApiStyle() == ModelApiStyle.DASHSCOPE_CHAT
                && !"https://dashscope.aliyuncs.com/api/v1/".equals(ModelEndpointPolicy.normalize(connection.getEndpoint()).toString())) {
            throw new IllegalStateException("Native DashScope custom endpoints are not supported by this SDK transport");
        }
    }
    public static Integer effectiveOutputLimit(Model model, Connection connection) {
        Integer configured = model.getDefaultOptions() == null ? null : model.getDefaultOptions().getMaxOutputTokens();
        if (configured != null) return configured;
        var profile = ModelProfiles.find(connection.getApiStyle(), connection.getCompatibilityProfile(), model.getCapabilityProfile());
        return profile == null ? null : profile.optionRules().defaultMaxOutputTokens();
    }
    public static boolean supportsThinking(String capability) {
        return ModelProfiles.all().stream().anyMatch(p -> p.capabilityProfile().equals(capability)
                && p.thinkingModes().stream().anyMatch(mode -> mode != ThinkingMode.DISABLED));
    }
    public static String effectiveReasoningEffort(Model model, Connection connection) {
        String configured = model.getDefaultOptions().getReasoningEffort();
        var profile = ModelProfiles.find(connection.getApiStyle(), connection.getCompatibilityProfile(), model.getCapabilityProfile());
        return configured == null && profile != null && model.getDefaultOptions().getThinkingMode() == ThinkingMode.ENABLED
                ? profile.optionRules().defaultReasoningEffort() : configured;
    }
}
