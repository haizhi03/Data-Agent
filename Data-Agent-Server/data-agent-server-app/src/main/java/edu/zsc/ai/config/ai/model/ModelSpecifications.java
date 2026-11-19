package edu.zsc.ai.config.ai.model;

import java.util.List;
import java.util.Locale;

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
    private static final List<Specification> MODELS = QwenModelSpecifications.all().stream().map(ModelSpecifications::qwen).toList();
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
