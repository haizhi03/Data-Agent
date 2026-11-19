package edu.zsc.ai.config.ai.model;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Provider-scoped model catalogue. Unknown model IDs remain configurable. */
public final class ModelSpecifications {
    private ModelSpecifications() {}
    public record Specification(String modelId, List<String> aliases, int contextTokens, int maxOutputTokens,
                                int maxThinkingOutputTokens, String sourceUrl, String verifiedAt,
                                String compatibilityProfile, String capabilityProfile) {
        public int outputLimit(MultiProviderProperties.ThinkingMode mode) {
            return mode == MultiProviderProperties.ThinkingMode.DISABLED ? maxOutputTokens : maxThinkingOutputTokens;
        }
    }
    private static Specification qwen(QwenModelSpecifications.Specification s) {
        return new Specification(s.modelId(),s.aliases(),s.contextTokens(),s.maxOutputTokens(),s.maxThinkingOutputTokens(),
                s.sourceUrl(),s.verifiedAt(),"qwen-chat-v1","qwen-tools-v1");
    }
    private static final List<Specification> DEEPSEEK = List.of(
            deepseek("deepseek-flash", List.of("deepseek-v4-flash")),
            deepseek("deepseek-v4-pro", List.of()));
    /**
     * Official docs publish the window as "1M" and the ceiling as "384K (393216)". The window therefore follows the
     * catalogue convention for 1M models (1_000_000, same as Qwen), while the ceiling uses the published integer.
     * Retired IDs stay resolvable as aliases.
     */
    private static Specification deepseek(String id, List<String> aliases) {
        return new Specification(id, aliases, 1_000_000, 393_216, 393_216,
                "https://api-docs.deepseek.com/quick_start/pricing", "2026-11-19",
                "deepseek-chat-v1", "deepseek-thinking-tools-v1");
    }
    private static final List<Specification> MODELS = Stream.concat(
            QwenModelSpecifications.all().stream().map(ModelSpecifications::qwen), DEEPSEEK.stream()).toList();
    public static List<Specification> all() { return MODELS; }
    public static List<Specification> all(String compatibility) { return all().stream().filter(s->s.compatibilityProfile().equals(compatibility)).toList(); }
    public static Specification find(String modelId) {
        String id=modelId==null?"":modelId.trim().toLowerCase(Locale.ROOT);
        var match=MODELS.stream().filter(s->s.modelId().equals(id) || s.aliases().contains(id)).findFirst();
        if(match.isPresent())return match.get();
        // Removed suggestions remain resolvable for existing saved configurations.
        var legacy=QwenModelSpecifications.find(id);
        return legacy==null?null:qwen(legacy);
    }
    public static Specification find(String modelId,String compatibility) {
        var result=find(modelId);
        return result!=null && result.compatibilityProfile().equals(compatibility)?result:null;
    }
}
