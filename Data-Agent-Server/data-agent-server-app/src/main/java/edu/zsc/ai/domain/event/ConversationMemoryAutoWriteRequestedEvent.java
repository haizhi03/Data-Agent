package edu.zsc.ai.domain.event;

import dev.langchain4j.invocation.InvocationParameters;
import org.springframework.context.ApplicationEvent;

public class ConversationMemoryAutoWriteRequestedEvent extends ApplicationEvent {

    private final Long conversationId;
    private final InvocationParameters invocationParameters;

    public ConversationMemoryAutoWriteRequestedEvent(Object source, Long conversationId) {
        this(source, conversationId, null);
    }

    public ConversationMemoryAutoWriteRequestedEvent(Object source, Long conversationId,
            InvocationParameters invocationParameters) {
        super(source);
        this.conversationId = conversationId;
        this.invocationParameters = invocationParameters;
    }

    public InvocationParameters getInvocationParameters() { return invocationParameters; }

    public Long getConversationId() {
        return conversationId;
    }
}
