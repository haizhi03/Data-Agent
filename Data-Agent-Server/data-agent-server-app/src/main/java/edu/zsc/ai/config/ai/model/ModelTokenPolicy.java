package edu.zsc.ai.config.ai.model;

import java.util.Locale;
import java.util.regex.Pattern;

/** Application defaults, not a claim that every provider offers the inferred capacity. */
public final class ModelTokenPolicy {
    private ModelTokenPolicy() {}
    public static final int FALLBACK_CONTEXT = 32768;
    public static final int MAX_OUTPUT = 1_000_000;
    private static final Pattern NAMED_WINDOW = Pattern.compile("(?:^|[-_/])(\\d{1,5})([km])(?:$|[-_/])");

    public static int context(String modelId, Integer override) {
        if (override != null) {
            if (override < 2 || override > 100_000_000) throw new IllegalArgumentException("Invalid context window");
            return override;
        }
        String name = modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
        var specification=QwenModelSpecifications.find(name);
        if(specification!=null)return specification.contextTokens();
        // Published GPT-4o/mini snapshots: https://developers.openai.com/api/docs/models/gpt-4o
        if (name.matches("gpt-4o(?:-mini)?(?:-\\d{4}-\\d{2}-\\d{2})?")) return 128000;
        var named = NAMED_WINDOW.matcher(name);
        if (named.find()) {
            long value = Long.parseLong(named.group(1)) * ("m".equals(named.group(2)) ? 1024L * 1024 : 1024L);
            if (value >= 2 && value <= 100_000_000) return (int) value;
        }
        // Unknown names (including gateways/local aliases) stay conservative and remain overridable.
        return FALLBACK_CONTEXT;
    }

    public static int output(Integer override, int context, MultiProviderProperties.ThinkingMode thinking,
                             Integer thinkingBudget, ModelPurpose purpose) {
        if (override != null && (override < 256 || override > MAX_OUTPUT)) throw new IllegalArgumentException("Invalid output limit");
        boolean reasoning = thinking != MultiProviderProperties.ThinkingMode.DISABLED;
        int chosen = override != null ? override : switch (purpose) {
            case MAIN_CHAT, EXPLORER, PLANNER -> 8192;
            case COMPRESSION, MEMORY_WRITER -> 2048;
        };
        if (reasoning) chosen = Math.max(chosen, 8192);
        if (override == null && thinkingBudget != null) {
            if (thinkingBudget < 1 || thinkingBudget >= MAX_OUTPUT) throw new IllegalArgumentException("Invalid thinking budget");
            chosen = Math.max(chosen, thinkingBudget + 1);
        }
        if (override == null && !reasoning) chosen = Math.min(chosen, context - 1);
        if (chosen < 1 || chosen >= context || chosen > MAX_OUTPUT) throw new IllegalArgumentException("Output exceeds context window");
        return chosen;
    }

    /** Strict validation for newly saved settings; automatic budgets remain below the model ceiling. */
    public static int output(String modelId,String compatibility,Integer override,int context,
                             MultiProviderProperties.ThinkingMode thinking,Integer thinkingBudget,ModelPurpose purpose) {
        var specification=QwenModelSpecifications.find(modelId,compatibility);
        if(specification!=null) {
            int ceiling=specification.outputLimit(thinking);
            if(context>specification.contextTokens() || (override!=null && override>ceiling))throw new IllegalArgumentException("Model token limit exceeded");
            int result=output(override,context,thinking,thinkingBudget,purpose);
            if(thinkingBudget!=null && thinkingBudget>=ceiling)throw new IllegalArgumentException("Thinking budget leaves no output space");
            return Math.min(result,ceiling);
        }
        return output(override,context,thinking,thinkingBudget,purpose);
    }
}
