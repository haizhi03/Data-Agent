package edu.zsc.ai.domain.event;

import dev.langchain4j.invocation.InvocationParameters;
import org.springframework.context.ApplicationEvent;

public class ChatCompletedEvent extends ApplicationEvent {

    private final Long conversationId;
    private final Integer outputTokens;
    private final Integer totalTokens;
    private final InvocationParameters invocationParameters;

    public ChatCompletedEvent(Object source,
                              Long conversationId,
                              Integer outputTokens,
                              Integer totalTokens) {
        this(source, conversationId, outputTokens, totalTokens, null);
    }

    public ChatCompletedEvent(Object source, Long conversationId, Integer outputTokens, Integer totalTokens,
                              InvocationParameters invocationParameters) {
        super(source);
        this.conversationId = conversationId;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.invocationParameters = invocationParameters;
    }

    public InvocationParameters getInvocationParameters() { return invocationParameters; }

    public Long getConversationId() {
        return conversationId;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }
}
