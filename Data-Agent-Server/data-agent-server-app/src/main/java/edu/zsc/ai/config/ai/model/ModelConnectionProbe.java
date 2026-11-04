package edu.zsc.ai.config.ai.model;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.FinishReason;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** One text request through the production adapters. Draft credentials and clients are never cached. */
@Component
public class ModelConnectionProbe {
    public void test(ResolvedModelConfig config, String apiKey) {
        // This credential provider is local to this invocation and can only serve its exact snapshot.
        var ephemeral = new CredentialService(null, null) {
            @Override public <T> T withSecret(ResolvedModelConfig requested, Function<String,T> action) {
                if (requested != config) throw ModelIntegrationException.unavailable();
                return action.apply(apiKey);
            }
        };
        var response = createClients(config, ephemeral).chatModel().chat(ChatRequest.builder()
                .messages(UserMessage.from("Reply with OK only.")).toolChoice(ToolChoice.NONE).build());
        if (response == null || response.aiMessage() == null) throw protocolError();
        if (response.finishReason() == FinishReason.LENGTH) throw new ModelIntegrationException("MODEL_OUTPUT_TRUNCATED", HttpStatus.BAD_GATEWAY);
        if (response.finishReason() == FinishReason.CONTENT_FILTER) throw new ModelIntegrationException("MODEL_REFUSED", HttpStatus.BAD_GATEWAY);
        if (response.finishReason() != FinishReason.STOP || response.aiMessage().hasToolExecutionRequests()
                || response.aiMessage().text() == null || response.aiMessage().text().isBlank()) throw protocolError();
    }
    /** Transport seam for local TLS fixtures; production always selects the same adapters as normal calls. */
    protected ModelClientBundle createClients(ResolvedModelConfig config, CredentialService ephemeral) {
        ModelClientFactory factory = switch (config.apiStyle()) {
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatClientFactory(ephemeral);
            case OPENAI_RESPONSES -> new ResponsesClientFactory(ephemeral);
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesClientFactory(ephemeral);
            case DASHSCOPE_CHAT -> new DashScopeClientFactory(ephemeral);
            default -> throw ModelIntegrationException.capability();
        };
        return factory.create(config);
    }
    private static ModelIntegrationException protocolError() {
        return new ModelIntegrationException("MODEL_PROTOCOL_ERROR", HttpStatus.BAD_GATEWAY);
    }
}
