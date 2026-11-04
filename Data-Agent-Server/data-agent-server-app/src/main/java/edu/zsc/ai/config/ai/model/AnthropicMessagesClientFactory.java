package edu.zsc.ai.config.ai.model;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnthropicMessagesClientFactory implements ModelClientFactory {
    private final CredentialService credentials;
    @Override public boolean supports(ModelApiStyle style) { return style == ModelApiStyle.ANTHROPIC_MESSAGES; }
    @Override public ModelClientBundle create(ResolvedModelConfig config) {
        var model = new AnthropicMessagesProtocolModel(config, credentials, new PolicyHttpClientBuilder(config).build());
        return new ModelClientBundle(model, model);
    }
}
