package edu.zsc.ai.domain.service.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import edu.zsc.ai.agent.memory.ChatMemoryCompressor;
import edu.zsc.ai.agent.tool.AgentToolTracker;
import edu.zsc.ai.common.constant.AgentRuntimeLoggerNames;
import edu.zsc.ai.common.enums.ai.ToolNameEnum;
import edu.zsc.ai.config.ai.model.ModelIntegrationException;
import edu.zsc.ai.context.AgentExecutionContext;
import edu.zsc.ai.context.ModelContextScope;
import edu.zsc.ai.domain.event.ChatCompletedEvent;
import edu.zsc.ai.domain.model.dto.response.agent.ChatResponseBlock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Bridges a LangChain4j {@link TokenStream} into a reactive
 * {@code Flux<ChatResponseBlock>} suitable for SSE streaming.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamBridge {

    private static final Logger runtimeLog = LoggerFactory.getLogger(AgentRuntimeLoggerNames.CONVERSATION);

    private final SseEmitterRegistry sseEmitterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMemoryCompressor chatMemoryCompressor;

    /**
     * Start the agent chat from the given session and bridge the resulting
     * {@link TokenStream} into a {@link Flux} of {@link ChatResponseBlock}.
     *
     * @param session     the prepared chat session (agent + parameters)
     * @param toolTracker shared tracker for recording tool invocations
     */
    public Flux<ChatResponseBlock> bridge(
            ChatSession session,
            AgentToolTracker toolTracker) {

        Long conversationId = session.conversationId();
        Sinks.Many<ChatResponseBlock> sink = Sinks.many().unicast().onBackpressureBuffer();
        sseEmitterRegistry.register(conversationId, sink);
        TokenStream tokenStream;
        try { tokenStream = session.startChat(); }
        catch (RuntimeException e) { sseEmitterRegistry.unregister(conversationId, sink); throw e; }
        var modelRun = session.agentRequestContextSnapshot() == null ? null : session.agentRequestContextSnapshot().getModelRunSnapshot();
        var terminal = new AtomicBoolean();
        log.debug("[ChatStream] sink registered for conversation {}", conversationId);
        Set<String> streamedToolCallIds = new HashSet<>();
        Map<String, Long> toolStartedAtById = new ConcurrentHashMap<>();
        Map<String, String> toolDescriptionById = new ConcurrentHashMap<>();
        StringBuilder responseText = new StringBuilder();
        StringBuilder thinkingText = new StringBuilder();

        tokenStream.onPartialResponse(content -> {
            if (StringUtils.isNotBlank(content)) {
                responseText.append(content);
                sink.tryEmitNext(ChatResponseBlock.text(content));
            }
        });

        tokenStream.onPartialThinking(partial -> {
            if (StringUtils.isNotBlank(partial.text())) {
                thinkingText.append(partial.text());
                sink.tryEmitNext(ChatResponseBlock.thought(partial.text()));
            }
        });

        tokenStream.onPartialToolCallWithContext((partialToolCall, context) -> {
            log.debug("Partial tool call: index={}, id={}, name={}, partialArgs='{}'",
                    partialToolCall.index(), partialToolCall.id(), partialToolCall.name(),
                    partialToolCall.partialArguments());

            if (Objects.nonNull(partialToolCall.id())) {
                streamedToolCallIds.add(partialToolCall.id());
                toolStartedAtById.computeIfAbsent(partialToolCall.id(), ignored -> System.currentTimeMillis());
                String description = ChatResponseBlock.extractToolDescription(partialToolCall.partialArguments());
                if (StringUtils.isNotBlank(description)) {
                    toolDescriptionById.put(partialToolCall.id(), description);
                }
                if (ToolNameEnum.isSubAgentTool(partialToolCall.name())) {
                    log.debug("SubAgent tool detected: setting parentToolCallId={}", partialToolCall.id());
                    AgentExecutionContext.setParentToolCallId(partialToolCall.id());
                }
                runtimeLog.info("conversation_tool_call conversationId={} toolCallId={} toolName={} streaming=true arguments={}",
                        conversationId,
                        partialToolCall.id(),
                        partialToolCall.name(),
                        StringUtils.defaultString(partialToolCall.partialArguments()));
            }

            sink.tryEmitNext(ChatResponseBlock.toolCall(
                    partialToolCall.id(),
                    partialToolCall.name(),
                    partialToolCall.partialArguments(),
                    true,
                    StringUtils.isNotBlank(partialToolCall.id()) ? toolDescriptionById.get(partialToolCall.id()) : null,
                    StringUtils.isNotBlank(partialToolCall.id()) ? toolStartedAtById.get(partialToolCall.id()) : null
            ));
        });

        tokenStream.onIntermediateResponse(response -> {
            if (!response.aiMessage().hasToolExecutionRequests()) {
                return;
            }
            for (ToolExecutionRequest toolRequest : response.aiMessage().toolExecutionRequests()) {
                if (streamedToolCallIds.contains(toolRequest.id())) {
                    log.debug("Skipping already-streamed tool call: id={}, name={}",
                            toolRequest.id(), toolRequest.name());
                    continue;
                }

                log.debug("Complete tool call (non-streaming provider): id={}, name={}",
                        toolRequest.id(), toolRequest.name());

                if (ToolNameEnum.isSubAgentTool(toolRequest.name())) {
                    log.debug("SubAgent tool detected (non-streaming): setting parentToolCallId={}", toolRequest.id());
                    AgentExecutionContext.setParentToolCallId(toolRequest.id());
                }
                if (StringUtils.isNotBlank(toolRequest.id())) {
                    toolStartedAtById.putIfAbsent(toolRequest.id(), System.currentTimeMillis());
                    String description = ChatResponseBlock.extractToolDescription(toolRequest.arguments());
                    if (StringUtils.isNotBlank(description)) {
                        toolDescriptionById.put(toolRequest.id(), description);
                    }
                }
                runtimeLog.info("conversation_tool_call conversationId={} toolCallId={} toolName={} streaming=false arguments={}",
                        conversationId,
                        toolRequest.id(),
                        toolRequest.name(),
                        StringUtils.defaultString(toolRequest.arguments()));

                Long startedAt = StringUtils.isNotBlank(toolRequest.id()) ? toolStartedAtById.get(toolRequest.id()) : null;
                String description = StringUtils.isNotBlank(toolRequest.id()) ? toolDescriptionById.get(toolRequest.id()) : null;
                sink.tryEmitNext(ChatResponseBlock.toolCall(
                        toolRequest.id(),
                        toolRequest.name(),
                        toolRequest.arguments(),
                        false,
                        description,
                        startedAt
                ));
            }
        });

        tokenStream.onToolExecuted(toolExecution -> {
            ToolExecutionRequest req = toolExecution.request();
            toolTracker.record(req.name());
            log.info("Tool executed: name={}, toolCallId={}, failed={}, resultLen={}",
                    req.name(), req.id(), toolExecution.hasFailed(),
                    toolExecution.result() != null ? toolExecution.result().toString().length() : 0);

            if (ToolNameEnum.isSubAgentTool(req.name())) {
                AgentExecutionContext.clear();
            }
            runtimeLog.info("conversation_tool_result conversationId={} toolCallId={} toolName={} failed={} resultLength={} result={}",
                    conversationId,
                    req.id(),
                    req.name(),
                    toolExecution.hasFailed(),
                    toolExecution.result() != null ? toolExecution.result().toString().length() : 0,
                    toolExecution.result());

            Long finishedAt = System.currentTimeMillis();
            Long startedAt = StringUtils.isNotBlank(req.id()) ? toolStartedAtById.get(req.id()) : null;
            String description = StringUtils.isNotBlank(req.id()) ? toolDescriptionById.get(req.id()) : null;
            sink.tryEmitNext(ChatResponseBlock.toolResult(
                    req.id(),
                    req.name(),
                    toolExecution.result(),
                    toolExecution.hasFailed(),
                    description,
                    startedAt,
                    finishedAt));
        });

        tokenStream.onCompleteResponse(response -> {
            if (!terminal.compareAndSet(false, true)) return;
            AgentExecutionContext.clear();
            sseEmitterRegistry.unregister(conversationId, sink);
            log.debug("[ChatStream] sink unregistered for conversation {}", conversationId);
            collectTokenUsage(response, toolTracker);
            log.info("Conversation {} completed: toolCount={}, outputTokens={}, totalTokens={}",
                    conversationId, toolTracker.getTotalCount(),
                    response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : null,
                    response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : null);
            runtimeLog.info("conversation_complete conversationId={} toolCount={} toolCounts={} outputTokens={} totalTokens={} responseLength={} thinkingLength={} response=\n{}\nconversation_thinking=\n{}",
                    conversationId,
                    toolTracker.getTotalCount(),
                    toolTracker.getToolCounts(),
                    response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : null,
                    response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : null,
                    responseText.length(),
                    thinkingText.length(),
                    responseText,
                    thinkingText);
            publishChatCompleted(response, conversationId, session.parameters());
            var metadata = buildDoneMetadata(conversationId, toolTracker);
            if (modelRun != null) { metadata.put("runId", modelRun.runId()); metadata.put("status", "SUCCEEDED"); }
            sink.tryEmitNext(ChatResponseBlock.doneBlock(metadata));
            sink.tryEmitComplete();
        });

        tokenStream.onError(error -> {
            if (!terminal.compareAndSet(false, true)) return;
            AgentExecutionContext.clear();
            log.error("Error in chat stream", error);
            runtimeLog.error("conversation_error conversationId={} responseLength={} thinkingLength={}",
                    conversationId,
                    responseText.length(),
                    thinkingText.length(),
                    error);
            sseEmitterRegistry.unregister(conversationId, sink);
            log.debug("[ChatStream] sink unregistered for conversation {} (on error)", conversationId);
            if (modelRun == null) sink.tryEmitError(error);
            else {
                String code = error instanceof ModelIntegrationException known ? known.getReason() : "MODEL_PROTOCOL_ERROR";
                String status = "MODEL_OUTPUT_TRUNCATED".equals(code) || "AGENT_BUDGET_EXCEEDED".equals(code) ? "INCOMPLETE" : "FAILED";
                sink.tryEmitNext(ChatResponseBlock.status(code));
                sink.tryEmitNext(ChatResponseBlock.doneBlock(Map.of("runId", modelRun.runId(), "status", status, "errorCode", code)));
                sink.tryEmitComplete();
            }
        });

        try (var ignored = new ModelContextScope(session.requestContextSnapshot(), session.agentRequestContextSnapshot())) {
            try { tokenStream.start(); }
            catch (RuntimeException e) { sseEmitterRegistry.unregister(conversationId, sink); throw e; }
        }
        return sink.asFlux().doOnCancel(() -> {
            if (terminal.compareAndSet(false, true)) {
                if (modelRun != null) modelRun.cancel();
                sseEmitterRegistry.unregister(conversationId, sink);
            }
        });
    }
    private void collectTokenUsage(
            ChatResponse response, AgentToolTracker toolTracker) {
        if (Objects.nonNull(response.tokenUsage())) {
            toolTracker.setTokenUsage(
                    response.tokenUsage().outputTokenCount(),
                    response.tokenUsage().totalTokenCount());
        }
    }

    /**
     * Always publishes {@link ChatCompletedEvent} when the stream completes successfully so downstream
     * listeners (token persistence, memory autowrite) run even if the provider omits token usage metadata.
     */
    private void publishChatCompleted(
            ChatResponse response,
            Long conversationId, InvocationParameters parameters) {
        Integer outputTokens = null;
        Integer totalTokens = null;
        if (response.tokenUsage() != null) {
            outputTokens = response.tokenUsage().outputTokenCount();
            totalTokens = response.tokenUsage().totalTokenCount();
        }

        if (Objects.nonNull(totalTokens) && totalTokens > 0) {
            log.info("Chat completed for conversation {}: {} total tokens (output: {})",
                    conversationId, totalTokens, outputTokens);
        } else {
            log.debug(
                    "Chat completed for conversation {} without usable token usage (memory autowrite still notified)",
                    conversationId);
        }

        eventPublisher.publishEvent(
                new ChatCompletedEvent(this, conversationId, outputTokens, totalTokens, parameters));
    }

    private Map<String, Object> buildDoneMetadata(Long conversationId, AgentToolTracker toolTracker) {
        Map<String, Object> metadata = new LinkedHashMap<>(toolTracker.toMetadata());
        metadata.putAll(chatMemoryCompressor.consumeDoneMetadata(conversationId));
        return metadata;
    }
}
