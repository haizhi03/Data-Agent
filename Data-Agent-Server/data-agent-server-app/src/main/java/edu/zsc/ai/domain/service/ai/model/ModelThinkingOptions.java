package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.*;
import java.util.List;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.*;

/** Thinking overrides produce immutable run options without editing shared model configurations. */
public final class ModelThinkingOptions {
    private ModelThinkingOptions() {}

    public static List<MultiProviderProperties.ThinkingMode> modes(FileModelState.Model model,FileModelState.Connection connection) {
        if(model==null || connection==null)return List.of();
        return modes(model.capabilityProfile(),connection.apiStyle(),connection.compatibilityProfile());
    }

    public static List<MultiProviderProperties.ThinkingMode> modes(String capability,ModelApiStyle style,String compatibility) {
        return ModelProfiles.all().stream().filter(profile->profile.apiStyle()==style
                && profile.compatibilityProfile().equals(compatibility) && profile.capabilityProfile().equals(capability))
                .findFirst().map(profile->profile.thinkingModes()).orElse(List.of());
    }

    /** Validated per-message main-chat options; never persisted to workspace defaults. */
    public static ResolvedModelConfig forRequest(ResolvedModelConfig base,MultiProviderProperties.ThinkingMode mode) {
        if(mode==null)return base;
        var source=new FileModelState.Connection(base.connectionId(),"request",base.name(),base.provider(),base.apiStyle(),base.endpoint().toString(),
                base.compatibilityProfile(),null,0,base.connectionVersion(),true,base.allowedPrivateHosts());
        var model=new FileModelState.Model(base.modelConfigId(),"request",base.connectionId(),base.name(),base.modelId(),base.capabilityProfile(),base.options(),
                base.maxContextTokens(),base.memoryThreshold(),base.chatVisible(),base.configVersion(),true);
        try {
            validate(model,source,mode,ModelPurpose.MAIN_CHAT);
            var options=options(model,source,mode,ModelPurpose.MAIN_CHAT);
            return new ResolvedModelConfig(base.modelConfigId(),base.configVersion(),base.connectionId(),base.connectionVersion(),base.access(),base.provider(),base.apiStyle(),
                    base.modelId(),base.name(),base.endpoint(),base.credentialRef(),base.credentialVersion(),base.capabilityProfile(),base.compatibilityProfile(),options,
                    base.maxContextTokens(),base.memoryThreshold(),base.chatVisible(),base.allowedPrivateHosts());
        }catch(ModelManagementException exception){throw ModelIntegrationException.capability();}
    }

    public static int context(FileModelState.Model model,FileModelState.Connection connection) {
        int context=model.tokenLimits()!=null && model.tokenLimits().maxContextTokens()==null?ModelTokenPolicy.context(model.modelId(),null):model.maxContextTokens();
        var specification=QwenModelSpecifications.find(model.modelId(),connection.compatibilityProfile());
        return specification==null?context:Math.min(context,specification.contextTokens());
    }

    public static ResolvedModelConfig.EffectiveOptions options(FileModelState.Model model,FileModelState.Connection connection,
            MultiProviderProperties.ThinkingMode override,ModelPurpose purpose) {
        var original=model.options();
        // A later model/profile edit may remove this capability. Its own valid defaults take precedence then.
        boolean apply=override!=null && modes(model,connection).contains(override);
        var mode=apply?override:original.thinkingMode();
        Integer budget=original.thinkingBudget();String effort=original.reasoningEffort();Double temperature=original.temperature();
        if(apply) {
            if(mode==DISABLED){budget=null;effort=null;}
            if("anthropic-thinking-v1".equals(model.capabilityProfile()) && mode==ENABLED && budget==null)budget=1024;
            if("anthropic-adaptive-v1".equals(model.capabilityProfile()))budget=null;
            if("responses-reasoning-v1".equals(model.capabilityProfile()) && mode==ENABLED && effort==null)effort="medium";
            if(connection.apiStyle()==ModelApiStyle.ANTHROPIC_MESSAGES && mode!=DISABLED)temperature=null;
        }
        int context=context(model,connection);
        var specification=QwenModelSpecifications.find(model.modelId(),connection.compatibilityProfile());
        Integer output=model.tokenLimits()==null?original.maxOutputTokens():model.tokenLimits().maxOutputTokens();
        if(specification!=null && output!=null)output=Math.min(output,specification.outputLimit(mode));
        if(model.tokenLimits()!=null || apply)output=ModelTokenPolicy.output(model.modelId(),connection.compatibilityProfile(),output,context,mode,budget,purpose);
        else if(specification!=null && output!=null)output=Math.min(context-1,output);
        return new ResolvedModelConfig.EffectiveOptions(mode,budget,temperature,output,effort);
    }

    public static void validate(FileModelState.Model model,FileModelState.Connection connection,MultiProviderProperties.ThinkingMode mode,ModelPurpose purpose) {
        if(mode!=null && !modes(model,connection).contains(mode))throw ModelManagementException.invalid();
        if(mode==null)return;
        try {
            var effective=options(model,connection,mode,purpose);
            var options=new MultiProviderProperties.Options();options.setThinkingMode(effective.thinkingMode());options.setThinkingBudget(effective.thinkingBudget());
            options.setTemperature(effective.temperature());options.setMaxOutputTokens(effective.maxOutputTokens());options.setReasoningEffort(effective.reasoningEffort());
            var definition=new MultiProviderProperties.Model();definition.setCapabilityProfile(model.capabilityProfile());definition.setMaxContextTokens(context(model,connection));definition.setDefaultOptions(options);
            var source=new MultiProviderProperties.Connection();source.setApiStyle(connection.apiStyle());source.setCompatibilityProfile(connection.compatibilityProfile());source.setEndpoint(connection.endpoint());
            ProviderCompatibilityPolicy.validate(definition,source);
        }catch(RuntimeException exception){throw ModelManagementException.invalid();}
    }
}
