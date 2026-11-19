package edu.zsc.ai.config.ai.model;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.*;

/** Closed business history can move between deployments; open tool chains cannot. */
public final class ProtocolHistory {
    private ProtocolHistory() {}

    /** Track the source of SDK reasoning so switching providers never replays foreign thoughts. */
    static ChatResponse capture(ChatResponse response, ResolvedModelConfig config) {
        if (response == null || response.aiMessage() == null || config.apiStyle() != ModelApiStyle.OPENAI_CHAT_COMPLETIONS
                || response.aiMessage().thinking() == null) return response;
        return response.toBuilder().aiMessage(ProtocolStateCodec.attach(response.aiMessage(), config, ProtocolJson.array())).build();
    }

    public static List<ChatMessage> forTarget(List<ChatMessage> messages, ResolvedModelConfig config) {
        checkClosed(messages);
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage ai) {
                var state = ProtocolStateCodec.read(ai);
                boolean legacyForeign = ai.attributes().containsKey("thinking_signature") || ai.attributes().containsKey("redacted_thinking");
                // Unsourced reasoning from a preserved-thinking profile cannot be replayed safely.
                boolean unknownThinking = state == null && config.apiStyle() == ModelApiStyle.OPENAI_CHAT_COMPLETIONS
                        && OpenAiChatCompatibility.rules(config).preserveThinking() && ai.thinking() != null;
                result.add((state != null && !ProtocolStateCodec.matches(state, config)) || legacyForeign || unknownThinking
                        ? ProtocolStateCodec.portable(ai) : ai);
            } else result.add(message);
        }
        return List.copyOf(result);
    }

    public static List<ChatMessage> forSummary(List<ChatMessage> messages) {
        checkClosed(messages);
        return messages.stream().map(m -> {
            if (!(m instanceof AiMessage ai)) return m;
            ProtocolStateCodec.read(ai);
            return ProtocolStateCodec.portable(ai);
        }).toList();
    }

    public static void checkClosed(List<ChatMessage> messages) {
        Map<String, String> pending = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (ChatMessage message : messages) advance(message, pending, seen);
        if (!pending.isEmpty()) throw ProtocolJson.incompatible();
    }

    private static void advance(ChatMessage message, Map<String, String> pending, Set<String> seen) {
        if (message instanceof ToolExecutionResultMessage tool) {
            if (tool.id() == null || !pending.containsKey(tool.id()) || !Objects.equals(pending.get(tool.id()), tool.toolName())) throw ProtocolJson.incompatible();
            pending.remove(tool.id());
        } else {
            if (!pending.isEmpty()) throw ProtocolJson.incompatible();
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                seen.clear(); // Call IDs may repeat across separate, already closed conversation turns.
                for (var call : ai.toolExecutionRequests()) {
                    if (call.id() == null || call.id().isBlank() || !seen.add(call.id()) || call.name() == null || call.name().isBlank()) throw ProtocolJson.incompatible();
                    pending.put(call.id(), call.name());
                }
            }
        }
    }

    /** Prefix must end before a new user turn for opaque protocol history. Returns 0 when unsafe. */
    public static int safeSplit(List<ChatMessage> messages, int targetIndex) {
        boolean opaque = messages.stream().anyMatch(m -> m instanceof AiMessage ai && ai.attributes().containsKey(ProtocolStateCodec.ATTRIBUTE));
        Map<String, String> pending = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int split = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (i > 0 && i <= targetIndex && pending.isEmpty()
                    && (message instanceof UserMessage || (!opaque && message instanceof AiMessage ai && !ai.hasToolExecutionRequests()))) split = i;
            advance(message, pending, seen);
        }
        return split;
    }
}
