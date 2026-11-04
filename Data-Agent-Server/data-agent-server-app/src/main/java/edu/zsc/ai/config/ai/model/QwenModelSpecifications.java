package edu.zsc.ai.config.ai.model;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Official Model Studio limits checked on 2026-10-24. Match snapshots explicitly, never by prefix. */
public final class QwenModelSpecifications {
    private QwenModelSpecifications() {}
    public record Specification(String modelId, List<String> aliases, int contextTokens, int maxOutputTokens,
                                int maxThinkingOutputTokens, String sourceUrl, String verifiedAt) {
        public int outputLimit(MultiProviderProperties.ThinkingMode mode) {
            return mode == MultiProviderProperties.ThinkingMode.DISABLED ? maxOutputTokens : maxThinkingOutputTokens;
        }
    }
    private static final List<Specification> MODELS = List.of(
            new Specification("qwen3.8-max",List.of("qwen3.8-max-0902","qwen3.8-max-2026-09-02"),1_000_000,131072,131072,
                    "https://help.aliyun.com/zh/model-studio/qwen3-8-max","2026-10-24"),
            new Specification("qwen3.6-flash",List.of(),1_000_000,65536,65536,
                    "https://help.aliyun.com/zh/model-studio/qwen3-6-flash","2026-10-24"),
            new Specification("qwen-plus",List.of("qwen-plus-2025-12-01"),1_000_000,32768,32768,
                    "https://help.aliyun.com/zh/model-studio/qwen-plus","2026-10-24"),
            new Specification("qwen3.5-plus",List.of("qwen3.5-plus-2026-02-15","qwen3.5-plus-2026-04-20"),1_000_000,65536,65536,
                    "https://help.aliyun.com/zh/model-studio/qwen3-5-plus","2026-10-24"));
    // Previously saved configurations retain their verified limits after removal from suggestions.
    private static final Specification LEGACY_MAX = new Specification("qwen3-max",List.of("qwen3-max-2026-01-23"),262144,65536,32768,
            "https://help.aliyun.com/zh/model-studio/model-qwen3-max","2026-10-24");
    public static List<Specification> all() { return MODELS; }
    public static Specification find(String modelId) {
        String id=modelId==null?"":modelId.trim().toLowerCase(Locale.ROOT);
        return Stream.concat(MODELS.stream(),Stream.of(LEGACY_MAX)).filter(s->s.modelId().equals(id) || s.aliases().contains(id)).findFirst().orElse(null);
    }
    public static Specification find(String modelId,String compatibility) {
        return "qwen-chat-v1".equals(compatibility)?find(modelId):null;
    }
}
