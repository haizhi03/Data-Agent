package edu.zsc.ai.config.ai.model;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ResponsesClientFactory implements ModelClientFactory {
    private final CredentialService credentials;
    @Override public boolean supports(ModelApiStyle style) { return style == ModelApiStyle.OPENAI_RESPONSES; }
    @Override public ModelClientBundle create(ResolvedModelConfig config) {
        var model = new ResponsesProtocolModel(config, credentials, new PolicyHttpClientBuilder(config).build());
        return new ModelClientBundle(model, model);
    }
}
