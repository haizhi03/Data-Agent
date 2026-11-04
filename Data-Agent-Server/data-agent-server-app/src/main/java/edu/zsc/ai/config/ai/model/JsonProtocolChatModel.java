package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small protocol boundary; AiServices still owns the agent loop, tools and memory. */
abstract class JsonProtocolChatModel implements ChatModel, StreamingChatModel {
    final ResolvedModelConfig config;
    private final CredentialService credentials;
    private final HttpClient http;

    JsonProtocolChatModel(ResolvedModelConfig config, CredentialService credentials, HttpClient http) {
        this.config = config; this.credentials = credentials; this.http = http;
    }
    abstract String resource();
    abstract ObjectNode encode(ChatRequest request, boolean stream);
    abstract ChatResponse decode(JsonNode response);
    abstract StreamDecoder streamDecoder(StreamingChatResponseHandler handler);

    interface StreamDecoder { void event(JsonNode event); }

    @Override public ChatResponse chat(ChatRequest request) {
        ChatRequest effective = prepare(request);
        return validateTools(decode(ProtocolJson.parse(http.execute(httpRequest(encode(effective, false))).body())), effective);
    }

    @Override public void chat(ChatRequest request, StreamingChatResponseHandler downstream) {
        ChatRequest effective = prepare(request);
        AtomicBoolean terminal = new AtomicBoolean();
        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            public void onPartialResponse(String text) { if (!terminal.get() && !text.isEmpty()) downstream.onPartialResponse(text); }
            public void onPartialThinking(PartialThinking thinking) { if (!terminal.get()) downstream.onPartialThinking(thinking); }
            public void onPartialToolCall(PartialToolCall call) {
                // Keep late-ID fragments inside the per-index decoder; the final complete call supplies the UI identity.
                if (!terminal.get() && call.id() != null && !call.id().isBlank()) downstream.onPartialToolCall(call);
            }
            public void onCompleteResponse(ChatResponse response) {
                try { validateTools(response, effective); } catch (RuntimeException error) { onError(error); return; }
                if (!terminal.compareAndSet(false, true)) return;
                try {
                    if (response.aiMessage().hasToolExecutionRequests()) {
                        var calls = response.aiMessage().toolExecutionRequests();
                        for (int i = 0; i < calls.size(); i++) downstream.onCompleteToolCall(new CompleteToolCall(i, calls.get(i)));
                    }
                    downstream.onCompleteResponse(response);
                } catch (RuntimeException error) { downstream.onError(ProtocolErrors.safe(error)); }
            }
            public void onError(Throwable error) {
                if (terminal.compareAndSet(false, true)) downstream.onError(ProtocolErrors.safe(error));
            }
        };
        StreamDecoder decoder = streamDecoder(handler);
        http.execute(httpRequest(encode(effective, true)), new DefaultServerSentEventParser(), new ServerSentEventListener() {
            @Override public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                onEvent(event);
                if (terminal.get()) context.parsingHandle().cancel();
            }
            @Override public void onEvent(ServerSentEvent event) {
                if (terminal.get()) return;
                try {
                    if (event.data() == null || event.data().isBlank()) return;
                    JsonNode value = ProtocolJson.parse(event.data());
                    if (event.event() != null && !event.event().isBlank() && !event.event().equals(value.path("type").asText())) throw ProtocolJson.invalid();
                    decoder.event(value);
                } catch (RuntimeException error) { handler.onError(error); }
            }
            @Override public void onClose() { if (!terminal.get()) handler.onError(ProtocolJson.invalid()); }
            @Override public void onError(Throwable error) { handler.onError(error); }
        });
    }

    private ChatRequest prepare(ChatRequest request) {
        if ((request.modelName() != null && !request.modelName().equals(config.modelId()))
                || request.topP() != null || request.topK() != null || request.frequencyPenalty() != null || request.presencePenalty() != null
                || (request.responseFormat() != null && request.responseFormat().type() != ResponseFormatType.TEXT)
                || (request.maxOutputTokens() != null && !request.maxOutputTokens().equals(config.options().maxOutputTokens()))
                || (request.temperature() != null && !request.temperature().equals(config.options().temperature()))) throw ModelIntegrationException.capability();
        return request.toBuilder().messages(ProtocolHistory.forTarget(request.messages(), config)).build();
    }

    private ChatResponse validateTools(ChatResponse response, ChatRequest request) {
        boolean tools = response.aiMessage().hasToolExecutionRequests();
        if (request.toolChoice() == ToolChoice.REQUIRED && !tools) throw ProtocolJson.invalid();
        Set<String> names = new HashSet<>();
        if (request.toolSpecifications() != null) request.toolSpecifications().forEach(tool -> names.add(tool.name()));
        if (tools) for (var call : response.aiMessage().toolExecutionRequests()) {
            if (request.toolChoice() == ToolChoice.NONE || !names.contains(call.name())) {
                throw new ModelIntegrationException("TOOL_FORBIDDEN", org.springframework.http.HttpStatus.BAD_GATEWAY);
            }
        }
        return response;
    }

    private HttpRequest httpRequest(ObjectNode body) {
        return credentials.withSecret(config, key -> {
            HttpRequest.Builder builder = HttpRequest.builder().method(HttpMethod.POST)
                    .url(config.endpoint().resolve(resource()).toString()).body(body.toString())
                    .addHeader("Content-Type", "application/json").addHeader("Accept", body.path("stream").asBoolean() ? "text/event-stream" : "application/json");
            if (config.apiStyle() == ModelApiStyle.ANTHROPIC_MESSAGES) {
                builder.addHeader("x-api-key", key).addHeader("anthropic-version", "2023-06-01");
            } else builder.addHeader("Authorization", "Bearer " + key);
            return builder.build();
        });
    }

    @Override public ChatRequestParameters defaultRequestParameters() {
        return ChatRequestParameters.builder().modelName(config.modelId()).temperature(config.options().temperature())
                .maxOutputTokens(config.options().maxOutputTokens()).build();
    }
    @Override public List<ChatModelListener> listeners() { return List.of(); }
    @Override public Set<Capability> supportedCapabilities() { return Set.of(); }
    @Override public ModelProvider provider() { return ModelProvider.OTHER; }
}
