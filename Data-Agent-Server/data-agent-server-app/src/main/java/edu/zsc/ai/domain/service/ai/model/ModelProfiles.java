package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.model.dto.response.ai.ModelManagementResponses.Profile;
import java.util.*;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.*;

/** The same closed set is used by validation and the management form. */
public final class ModelProfiles {
    private ModelProfiles() {}
    private static final ModelOptionRules TEXT = rules(List.of(),null,null,false,List.of(DISABLED),2,false,null,256,8192);
    private static final ModelOptionRules QWEN = rules(List.of(),null,1,false,List.of(DISABLED,ENABLED),2,false,null,256,8192);
    private static final ModelOptionRules RESPONSES_TEXT = rules(List.of(),null,null,false,List.of(DISABLED),2,false,4096,256,8192);
    private static final ModelOptionRules RESPONSES_REASONING = rules(List.of("low","medium","high"),"medium",null,false,List.of(),2,false,4096,8192,8192);
    private static final ModelOptionRules ANTHROPIC_TEXT = rules(List.of(),null,null,false,List.of(DISABLED),1,false,4096,256,8192);
    private static final ModelOptionRules ANTHROPIC_THINKING = rules(List.of(),null,1024,true,List.of(DISABLED),1,false,4096,256,8192);
    // DeepSeek defaults to thinking with effort "high"; temperature only applies outside thinking mode.
    private static final ModelOptionRules DEEPSEEK = rules(List.of("low","high","max"),"high",null,false,List.of(DISABLED),2,true,16384,8192,16384);
    private static final List<Profile> PROFILES = List.of(
        profile(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"openai-chat-v1","text-tools-v1",List.of(DISABLED),"chat/completions",TEXT),
        profile(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"qwen-chat-v1","qwen-tools-v1",List.of(DISABLED,ENABLED),"chat/completions",QWEN),
        profile(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"qwen-chat-v1","text-tools-v1",List.of(DISABLED),"chat/completions",TEXT),
        profile(ModelApiStyle.DASHSCOPE_CHAT,"qwen-chat-v1","qwen-tools-v1",List.of(DISABLED,ENABLED),"services/aigc/text-generation/generation",QWEN),
        profile(ModelApiStyle.DASHSCOPE_CHAT,"qwen-chat-v1","text-tools-v1",List.of(DISABLED),"services/aigc/text-generation/generation",TEXT),
        profile(ModelApiStyle.OPENAI_RESPONSES,"openai-responses-v1","responses-tools-v1",List.of(DISABLED),"responses",RESPONSES_TEXT),
        profile(ModelApiStyle.OPENAI_RESPONSES,"openai-responses-v1","responses-reasoning-v1",List.of(ENABLED),"responses",RESPONSES_REASONING),
        profile(ModelApiStyle.ANTHROPIC_MESSAGES,"anthropic-messages-v1","anthropic-tools-v1",List.of(DISABLED),"messages",ANTHROPIC_TEXT),
        profile(ModelApiStyle.ANTHROPIC_MESSAGES,"anthropic-messages-v1","anthropic-thinking-v1",List.of(DISABLED,ENABLED),"messages",ANTHROPIC_THINKING),
        profile(ModelApiStyle.ANTHROPIC_MESSAGES,"anthropic-messages-v1","anthropic-adaptive-v1",List.of(DISABLED,ADAPTIVE),"messages",ANTHROPIC_TEXT),
        // Thinking stays switchable: the provider enables it by default, so ENABLED must lead the list.
        profile(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"deepseek-chat-v1","deepseek-thinking-tools-v1",List.of(ENABLED,DISABLED),"chat/completions",DEEPSEEK)
    );
    public static List<Profile> all() { return PROFILES; }
    public static Profile find(ModelApiStyle style,String compatibility,String capability) {
        return PROFILES.stream().filter(p->p.apiStyle()==style && p.compatibilityProfile().equals(compatibility)
                && p.capabilityProfile().equals(capability)).findFirst().orElse(null);
    }
    /** The first profile is the connection's preferred capability, including always-thinking models. */
    public static Profile defaults(ModelApiStyle style,String compatibility) {
        return PROFILES.stream().filter(p->p.apiStyle()==style && p.compatibilityProfile().equals(compatibility))
                .findFirst().orElseThrow(ModelManagementException::invalid);
    }
    public static MultiProviderProperties.Options defaultOptions(Profile profile) {
        var options=new MultiProviderProperties.Options();
        options.setThinkingMode(profile.thinkingModes().get(0));
        if(options.getThinkingMode()==ENABLED) {
            options.setReasoningEffort(profile.optionRules().defaultReasoningEffort());
            if(profile.optionRules().thinkingBudgetRequired())options.setThinkingBudget(profile.optionRules().minThinkingBudget());
        }
        return options;
    }
    private static Profile profile(ModelApiStyle style,String compatibility,String capability,List<MultiProviderProperties.ThinkingMode> modes,String resource,ModelOptionRules rules) {
        return new Profile(style,compatibility,capability,modes,resource,rules);
    }
    private static ModelOptionRules rules(List<String> efforts,String defaultEffort,Integer budget,boolean required,
            List<MultiProviderProperties.ThinkingMode> temperatures,double maxTemperature,boolean preserve,Integer output,int probe,int minimumOutput) {
        return new ModelOptionRules(efforts,defaultEffort,budget,required,temperatures,maxTemperature,preserve,output,probe,minimumOutput);
    }
    public static String resource(ModelApiStyle style) { return all().stream().filter(p -> p.apiStyle()==style).findFirst().orElseThrow(ModelManagementException::invalid).resource(); }
    public static void connection(ModelApiStyle style, String compatibility, String endpoint) {
        if (all().stream().noneMatch(p -> p.apiStyle()==style && p.compatibilityProfile().equals(compatibility))) throw ModelManagementException.invalid();
        try {
            var base = ModelEndpointPolicy.normalize(endpoint);
            if (style == ModelApiStyle.DASHSCOPE_CHAT && !base.toString().equals("https://dashscope.aliyuncs.com/api/v1/")) throw ModelManagementException.invalid();
        } catch (RuntimeException e) { throw ModelManagementException.invalid(); }
    }
}
