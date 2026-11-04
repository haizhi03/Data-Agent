package edu.zsc.ai.config.ai.model;


import static edu.zsc.ai.config.ai.model.MultiProviderProperties.*;

/** Versioned, closed option profiles. Unknown protocols and profiles fail at startup. */
public final class ProviderCompatibilityPolicy {
    private ProviderCompatibilityPolicy() {}
    public static void validate(Model model, Connection connection) {
        boolean qwen = "qwen-chat-v1".equals(connection.getCompatibilityProfile());
        boolean qwenCapability = "qwen-tools-v1".equals(model.getCapabilityProfile());
        String capability = model.getCapabilityProfile();
        String policy = connection.getCompatibilityProfile();
        boolean responsesReasoning = "responses-reasoning-v1".equals(capability);
        boolean manual = "anthropic-thinking-v1".equals(capability);
        boolean adaptive = "anthropic-adaptive-v1".equals(capability);
        boolean supported = connection.getApiStyle() != null && switch (connection.getApiStyle()) {
            case OPENAI_CHAT_COMPLETIONS -> (qwen && (qwenCapability || "text-tools-v1".equals(capability)))
                    || ("openai-chat-v1".equals(policy) && "text-tools-v1".equals(capability));
            case DASHSCOPE_CHAT -> qwen && (qwenCapability || "text-tools-v1".equals(capability));
            case OPENAI_RESPONSES -> "openai-responses-v1".equals(policy) && (responsesReasoning || "responses-tools-v1".equals(capability));
            case ANTHROPIC_MESSAGES -> "anthropic-messages-v1".equals(policy) && (manual || adaptive || "anthropic-tools-v1".equals(capability));
            default -> false;
        };
        if (!supported) {
            throw new IllegalStateException("Unsupported model protocol or capability/compatibility profile");
        }
        Options o = model.getDefaultOptions();
        Integer output = effectiveOutputLimit(model, connection);
        if (o == null || o.getThinkingMode() == null
                || (o.getThinkingMode() == ThinkingMode.ENABLED && !qwenCapability && !responsesReasoning && !manual)
                || (o.getThinkingMode() == ThinkingMode.ADAPTIVE && !adaptive)
                || (responsesReasoning && o.getThinkingMode() != ThinkingMode.ENABLED)
                || (o.getThinkingBudget() != null && (o.getThinkingBudget() < 1 || o.getThinkingBudget() >= model.getMaxContextTokens()
                    || o.getThinkingMode() != ThinkingMode.ENABLED || (!qwenCapability && !manual)))
                || (o.getTemperature() != null && (!Double.isFinite(o.getTemperature()) || o.getTemperature() < 0 || o.getTemperature() > 2))
                || (output != null && (output < 1 || output >= model.getMaxContextTokens()))
                || (o.getThinkingBudget() != null && output != null && (manual ? o.getThinkingBudget() >= output : o.getThinkingBudget() > output))
                || (responsesReasoning && o.getTemperature() != null)
                || (connection.getApiStyle() == ModelApiStyle.ANTHROPIC_MESSAGES && o.getTemperature() != null && o.getTemperature() > 1)
                || (o.getThinkingMode() != ThinkingMode.DISABLED && connection.getApiStyle() == ModelApiStyle.ANTHROPIC_MESSAGES && o.getTemperature() != null)
                || (manual && o.getThinkingMode() == ThinkingMode.ENABLED && (o.getThinkingBudget() == null || o.getThinkingBudget() < 1024))
                || (o.getReasoningEffort() != null && (!responsesReasoning || o.getThinkingMode() != ThinkingMode.ENABLED
                    || !java.util.Set.of("low", "medium", "high").contains(o.getReasoningEffort())))) {
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
        if (connection.getApiStyle() == ModelApiStyle.ANTHROPIC_MESSAGES || connection.getApiStyle() == ModelApiStyle.OPENAI_RESPONSES) return 4096;
        return null;
    }
    public static boolean supportsThinking(String profile) {
        return java.util.Set.of("qwen-tools-v1", "responses-reasoning-v1", "anthropic-thinking-v1", "anthropic-adaptive-v1").contains(profile);
    }
    public static String effectiveReasoningEffort(Model model, Connection connection) {
        String configured = model.getDefaultOptions().getReasoningEffort();
        return configured == null && connection.getApiStyle() == ModelApiStyle.OPENAI_RESPONSES
                && model.getDefaultOptions().getThinkingMode() == ThinkingMode.ENABLED ? "medium" : configured;
    }
}
