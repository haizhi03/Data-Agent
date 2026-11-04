package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.model.dto.response.ai.ModelManagementResponses.Profile;
import java.util.*;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.*;

/** The same closed set is used by validation and the management form. */
public final class ModelProfiles {
    private ModelProfiles() {}
    public static List<Profile> all() { return List.of(
        new Profile(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"openai-chat-v1","text-tools-v1",List.of(DISABLED),"chat/completions"),
        new Profile(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"qwen-chat-v1","qwen-tools-v1",List.of(DISABLED,ENABLED),"chat/completions"),
        new Profile(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"qwen-chat-v1","text-tools-v1",List.of(DISABLED),"chat/completions"),
        new Profile(ModelApiStyle.DASHSCOPE_CHAT,"qwen-chat-v1","qwen-tools-v1",List.of(DISABLED,ENABLED),"services/aigc/text-generation/generation"),
        new Profile(ModelApiStyle.DASHSCOPE_CHAT,"qwen-chat-v1","text-tools-v1",List.of(DISABLED),"services/aigc/text-generation/generation"),
        new Profile(ModelApiStyle.OPENAI_RESPONSES,"openai-responses-v1","responses-tools-v1",List.of(DISABLED),"responses"),
        new Profile(ModelApiStyle.OPENAI_RESPONSES,"openai-responses-v1","responses-reasoning-v1",List.of(ENABLED),"responses"),
        new Profile(ModelApiStyle.ANTHROPIC_MESSAGES,"anthropic-messages-v1","anthropic-tools-v1",List.of(DISABLED),"messages"),
        new Profile(ModelApiStyle.ANTHROPIC_MESSAGES,"anthropic-messages-v1","anthropic-thinking-v1",List.of(DISABLED,ENABLED),"messages"),
        new Profile(ModelApiStyle.ANTHROPIC_MESSAGES,"anthropic-messages-v1","anthropic-adaptive-v1",List.of(DISABLED,ADAPTIVE),"messages")
    ); }
    public static String resource(ModelApiStyle style) { return all().stream().filter(p -> p.apiStyle()==style).findFirst().orElseThrow(ModelManagementException::invalid).resource(); }
    public static void connection(ModelApiStyle style, String compatibility, String endpoint) {
        if (all().stream().noneMatch(p -> p.apiStyle()==style && p.compatibilityProfile().equals(compatibility))) throw ModelManagementException.invalid();
        try {
            var base = ModelEndpointPolicy.normalize(endpoint);
            if (style == ModelApiStyle.DASHSCOPE_CHAT && !base.toString().equals("https://dashscope.aliyuncs.com/api/v1/")) throw ModelManagementException.invalid();
        } catch (RuntimeException e) { throw ModelManagementException.invalid(); }
    }
}
