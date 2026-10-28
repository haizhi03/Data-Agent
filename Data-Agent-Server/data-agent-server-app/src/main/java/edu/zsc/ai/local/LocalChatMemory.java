package edu.zsc.ai.local;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import edu.zsc.ai.config.ai.model.ModelIntegrationException;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Each run stages its own history. Cancellation or a late SDK callback cannot change committed history. */
final class LocalChatMemory implements ChatMemory {
    private final List<ChatMessage> messages;
    private final int maxCharacters;
    LocalChatMemory(List<ChatMessage> history, int maxCharacters) {
        this.messages = new ArrayList<>(history); this.maxCharacters = maxCharacters;
    }
    public Object id() { return this; }
    public synchronized void add(ChatMessage message) {
        if (characters() + message.toString().length() > maxCharacters || messages.size() >= 256) {
            throw new ModelIntegrationException("LOCAL_CONTEXT_LIMIT", HttpStatus.CONFLICT);
        }
        // AiServices may install the same system message again when a new run uses committed history.
        if (message instanceof dev.langchain4j.data.message.SystemMessage) messages.removeIf(m -> m instanceof dev.langchain4j.data.message.SystemMessage);
        messages.add(message);
    }
    public synchronized List<ChatMessage> messages() { return List.copyOf(messages); }
    public synchronized void clear() { messages.clear(); }
    synchronized int characters() { return messages.stream().mapToInt(m -> m.toString().length()).sum(); }
}
