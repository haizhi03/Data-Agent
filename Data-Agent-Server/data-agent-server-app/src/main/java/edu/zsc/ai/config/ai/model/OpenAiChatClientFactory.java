package edu.zsc.ai.config.ai.model;

import dev.langchain4j.model.openai.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.ENABLED;

@Component
@RequiredArgsConstructor
public class OpenAiChatClientFactory implements ModelClientFactory {
    private final CredentialService credentials;
    public boolean supports(ModelApiStyle style) { return style == ModelApiStyle.OPENAI_CHAT_COMPLETIONS; }
    public ModelClientBundle create(ResolvedModelConfig config) {
        var options = config.options();
        var builder = OpenAiChatRequestParameters.builder();
        builder.temperature(options.temperature()).maxOutputTokens(options.maxOutputTokens());
        if ("qwen-chat-v1".equals(config.compatibilityProfile())) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("enable_thinking", options.thinkingMode() == ENABLED);
            if (options.thinkingMode() == ENABLED && options.thinkingBudget() != null) parameters.put("thinking_budget", options.thinkingBudget());
            builder.customParameters(parameters);
        }
        var parameters = builder.build();
        boolean publicThinking = "qwen-chat-v1".equals(config.compatibilityProfile()) && options.thinkingMode() == ENABLED;
        return credentials.withSecret(config, secret -> new ModelClientBundle(
                OpenAiChatModel.builder().apiKey(secret).baseUrl(config.endpoint().toString()).modelName(config.modelId())
                        .returnThinking(publicThinking)
                        .defaultRequestParameters(parameters).httpClientBuilder(new PolicyHttpClientBuilder(config))
                        .maxRetries(0).timeout(Duration.ofSeconds(120)).logRequests(false).logResponses(false).build(),
                OpenAiStreamingChatModel.builder().apiKey(secret).baseUrl(config.endpoint().toString()).modelName(config.modelId())
                        .returnThinking(publicThinking)
                        .defaultRequestParameters(parameters).httpClientBuilder(new PolicyHttpClientBuilder(config))
                        .timeout(Duration.ofSeconds(120)).logRequests(false).logResponses(false).build()));
    }
}
