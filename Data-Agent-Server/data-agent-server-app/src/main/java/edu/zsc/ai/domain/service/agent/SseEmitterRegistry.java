package edu.zsc.ai.domain.service.agent;

import edu.zsc.ai.domain.model.dto.response.agent.ChatResponseBlock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Sinks;

@Component
public class SseEmitterRegistry {

    private final ConcurrentHashMap<Long, Sinks.Many<ChatResponseBlock>> sinks = new ConcurrentHashMap<>();

    public void register(Long conversationId, Sinks.Many<ChatResponseBlock> sink) {
        if (sinks.putIfAbsent(conversationId, sink) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AGENT_CONVERSATION_BUSY");
        }
    }

    public void unregister(Long conversationId) {
        sinks.remove(conversationId);
    }
    public void unregister(Long conversationId, Sinks.Many<ChatResponseBlock> sink) { sinks.remove(conversationId, sink); }

    public Optional<Sinks.Many<ChatResponseBlock>> get(Long conversationId) {
        return Optional.ofNullable(sinks.get(conversationId));
    }
}
