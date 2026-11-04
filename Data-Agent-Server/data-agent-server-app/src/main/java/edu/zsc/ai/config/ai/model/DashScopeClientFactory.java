package edu.zsc.ai.config.ai.model;

import dev.langchain4j.community.model.dashscope.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.ENABLED;

@Component
@RequiredArgsConstructor
public class DashScopeClientFactory implements ModelClientFactory {
    private final CredentialService credentials;
    public boolean supports(ModelApiStyle style) { return style == ModelApiStyle.DASHSCOPE_CHAT; }
    public ModelClientBundle create(ResolvedModelConfig config) {
        // The locked native SDK does not expose per-client DNS/HTTP transport controls.
        // Only its fixed official endpoint is accepted in M1; custom deployments use the compatible factory.
        if (!"https://dashscope.aliyuncs.com/api/v1/".equals(config.endpoint().toString())) throw ModelIntegrationException.capability();
        var o = config.options();
        var params = QwenChatRequestParameters.builder().enableThinking(o.thinkingMode() == ENABLED)
                .enableSanitizeMessages(false).thinkingBudget(o.thinkingBudget());
        params.temperature(o.temperature()).maxOutputTokens(o.maxOutputTokens());
        var parameters = params.build();
        return credentials.withSecret(config, key -> new ModelClientBundle(
                QwenChatModel.builder().apiKey(key).modelName(config.modelId()).defaultRequestParameters(parameters).build(),
                QwenStreamingChatModel.builder().apiKey(key).modelName(config.modelId()).defaultRequestParameters(parameters).build()));
    }
}
