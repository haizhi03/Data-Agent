package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.ModelContextScope;
import edu.zsc.ai.context.RequestContext;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ModelClientRegistry {
    private final List<ModelClientFactory> factories;
    private final ModelConfigResolver resolver;
    private final CredentialService credentials;
    // ResolvedModelConfig is immutable and includes all versions/options, but no secret material.
    private final Cache<ResolvedModelConfig, ModelClientBundle> cache = Caffeine.newBuilder()
            .maximumSize(256).expireAfterAccess(Duration.ofMinutes(30)).build();

    public ModelClientBundle clients(ModelRunSnapshot run, ModelPurpose purpose) {
        ResolvedModelConfig config = run.model(purpose);
        if (config == null) throw ModelIntegrationException.unavailable();
        resolver.reauthorize(config); // A cache hit never bypasses authorization.
        ModelClientBundle raw = cache.get(config, c -> {
            List<ModelClientFactory> matching = factories.stream().filter(f -> f.supports(c.apiStyle())).toList();
            if (matching.size() != 1) throw ModelIntegrationException.capability();
            try { return matching.get(0).create(c); }
            catch (RuntimeException e) { throw safe(e); }
        });
        return guarded(raw, config, run);
    }
    private void beforeCall(ResolvedModelConfig config, ModelRunSnapshot run) {
        resolver.reauthorize(config);
        credentials.withSecret(config, ignored -> Boolean.TRUE);
        run.beforeCall();
    }
    private ModelClientBundle guarded(ModelClientBundle raw, ResolvedModelConfig config, ModelRunSnapshot run) {
        var requestContext = RequestContext.snapshot();
        var agentContext = AgentRequestContext.snapshot();
        ChatModel chat = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                beforeCall(config, run);
                try (var ignored = new ModelContextScope(requestContext, agentContext)) {
                    ChatRequest effective = request.toBuilder().messages(ProtocolHistory.forTarget(request.messages(), config)).build();
                    ChatResponse response = raw.chatModel().chat(effective);
                    validateResponse(response, request);
                    resolver.reauthorize(config);
                    run.ensureActive();
                    return response;
                }
                catch (RuntimeException e) { throw safe(e); }
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return raw.chatModel().defaultRequestParameters(); }
            @Override public Set<Capability> supportedCapabilities() { return raw.chatModel().supportedCapabilities(); }
            @Override public ModelProvider provider() { return raw.chatModel().provider(); }
            @Override public List<ChatModelListener> listeners() { return raw.chatModel().listeners(); }
        };
        StreamingChatModel streaming = new StreamingChatModel() {
            @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                beforeCall(config, run);
                try (var ignored = new ModelContextScope(requestContext, agentContext)) {
                    ChatRequest effective = request.toBuilder().messages(ProtocolHistory.forTarget(request.messages(), config)).build();
                    raw.streamingChatModel().chat(effective, new StreamingChatResponseHandler() {
                    private final AtomicBoolean terminal = new AtomicBoolean();
                    @Override public void onPartialResponse(String text) { if (!terminal.get()) handler.onPartialResponse(text); }
                    @Override public void onPartialResponse(PartialResponse partial, PartialResponseContext context) { if (!terminal.get()) handler.onPartialResponse(partial, context); }
                    @Override public void onPartialThinking(PartialThinking thinking) { if (!terminal.get()) handler.onPartialThinking(thinking); }
                    @Override public void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) { if (!terminal.get()) handler.onPartialThinking(thinking, context); }
                    @Override public void onCompleteToolCall(CompleteToolCall call) { /* Released from the final validated response only. */ }
                    @Override public void onPartialToolCall(PartialToolCall call) { if (!terminal.get()) handler.onPartialToolCall(call); }
                    @Override public void onPartialToolCall(PartialToolCall call, PartialToolCallContext context) { if (!terminal.get()) handler.onPartialToolCall(call, context); }
                    @Override public void onCompleteResponse(ChatResponse response) {
                        if (!terminal.compareAndSet(false, true)) return;
                        try {
                            validateResponse(response, request);
                            resolver.reauthorize(config);
                            run.ensureActive();
                        } catch (RuntimeException error) { handler.onError(safe(error)); return; }
                        try (var ignored = new ModelContextScope(requestContext, agentContext)) {
                            if (response.aiMessage().hasToolExecutionRequests()) {
                                var calls = response.aiMessage().toolExecutionRequests();
                                for (int index = 0; index < calls.size(); index++) handler.onCompleteToolCall(new CompleteToolCall(index, calls.get(index)));
                            }
                            handler.onCompleteResponse(response);
                        } catch (RuntimeException error) { handler.onError(safe(error)); }
                    }
                    @Override public void onError(Throwable error) {
                        if (terminal.compareAndSet(false, true)) handler.onError(safe(error));
                    }
                }); } catch (RuntimeException e) { throw safe(e); }
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return raw.streamingChatModel().defaultRequestParameters(); }
            @Override public Set<Capability> supportedCapabilities() { return raw.streamingChatModel().supportedCapabilities(); }
            @Override public ModelProvider provider() { return raw.streamingChatModel().provider(); }
            @Override public List<ChatModelListener> listeners() { return raw.streamingChatModel().listeners(); }
        };
        return new ModelClientBundle(chat, streaming);
    }
    private static void validateResponse(ChatResponse response, ChatRequest request) {
        if (response == null || response.aiMessage() == null || response.finishReason() == null) {
            throw new ModelIntegrationException("MODEL_PROTOCOL_ERROR", HttpStatus.BAD_GATEWAY);
        }
        switch (response.finishReason()) {
            case LENGTH -> throw new ModelIntegrationException("MODEL_OUTPUT_TRUNCATED", HttpStatus.BAD_GATEWAY);
            case CONTENT_FILTER -> throw new ModelIntegrationException("MODEL_REFUSED", HttpStatus.BAD_GATEWAY);
            case OTHER -> throw new ModelIntegrationException("MODEL_PROTOCOL_ERROR", HttpStatus.BAD_GATEWAY);
            default -> { }
        }
        if (response.aiMessage().hasToolExecutionRequests()) {
            if (request.toolChoice() == ToolChoice.NONE) throw new ModelIntegrationException("TOOL_FORBIDDEN", HttpStatus.BAD_GATEWAY);
            Set<String> ids = new HashSet<>();
            Set<String> names = request.toolSpecifications() == null ? Set.of() : request.toolSpecifications().stream()
                    .map(ToolSpecification::name).collect(Collectors.toSet());
            for (var call : response.aiMessage().toolExecutionRequests()) {
                if (call.id() == null || call.id().isBlank() || !ids.add(call.id())
                        || call.name() == null || call.name().isBlank() || call.arguments() == null) {
                    throw new ModelIntegrationException("MODEL_PROTOCOL_ERROR", HttpStatus.BAD_GATEWAY);
                }
                if (!names.contains(call.name())) throw new ModelIntegrationException("TOOL_FORBIDDEN", HttpStatus.BAD_GATEWAY);
                try {
                    if (!TOOL_JSON.readTree(call.arguments()).isObject()) throw new IllegalArgumentException();
                } catch (Exception e) { throw new ModelIntegrationException("TOOL_VALIDATION_FAILED", HttpStatus.BAD_GATEWAY); }
            }
        }
    }
    private static final ObjectMapper TOOL_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static RuntimeException safe(Throwable error) {
        String code = "MODEL_PROTOCOL_ERROR";
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof ModelIntegrationException known) return known;
            if (error instanceof dev.langchain4j.exception.AuthenticationException) code = "MODEL_AUTH_FAILED";
            else if (error instanceof dev.langchain4j.exception.RateLimitException) code = "MODEL_RATE_LIMITED";
            else if (error instanceof dev.langchain4j.exception.TimeoutException) code = "MODEL_UPSTREAM_TIMEOUT";
        }
        return new ModelIntegrationException(code, HttpStatus.BAD_GATEWAY);
    }
}
