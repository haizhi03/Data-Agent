package edu.zsc.ai.config.ai.model;

import dev.langchain4j.model.openai.*;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.ENABLED;

@Component
@RequiredArgsConstructor
public class OpenAiChatClientFactory implements ModelClientFactory {
    private final CredentialService credentials;
    public boolean supports(ModelApiStyle style) { return style == ModelApiStyle.OPENAI_CHAT_COMPLETIONS; }
    public ModelClientBundle create(ResolvedModelConfig config) {
        return create(config, credentials);
    }
    @Override public ModelClientBundle create(ResolvedModelConfig config, CredentialService credentials) {
        var options = config.options();
        var builder = OpenAiChatRequestParameters.builder();
        builder.temperature(options.temperature()).maxOutputTokens(options.maxOutputTokens());
        OpenAiChatCompatibility.forConfig(config).configure(builder, config);
        var parameters = builder.build();
        boolean publicThinking = options.thinkingMode() == ENABLED;
        boolean sendThinking = publicThinking && OpenAiChatCompatibility.rules(config).preserveThinking();
        return credentials.withSecret(config, secret -> new ModelClientBundle(
                OpenAiChatModel.builder().apiKey(secret).baseUrl(config.endpoint().toString()).modelName(config.modelId())
                        .returnThinking(publicThinking)
                        .sendThinking(sendThinking)
                        .defaultRequestParameters(parameters).httpClientBuilder(httpClientBuilder(config))
                        .maxRetries(0).timeout(Duration.ofSeconds(120)).logRequests(false).logResponses(false).build(),
                OpenAiStreamingChatModel.builder().apiKey(secret).baseUrl(config.endpoint().toString()).modelName(config.modelId())
                        .returnThinking(publicThinking)
                        .sendThinking(sendThinking)
                        .defaultRequestParameters(parameters).httpClientBuilder(httpClientBuilder(config))
                        .timeout(Duration.ofSeconds(120)).logRequests(false).logResponses(false).build()));
    }
    protected dev.langchain4j.http.client.HttpClientBuilder httpClientBuilder(ResolvedModelConfig config) {
        return new PolicyHttpClientBuilder(config);
    }
}
