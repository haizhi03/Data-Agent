package edu.zsc.ai.domain.service.ai.autowrite;

public interface ConversationMemoryAdvisoryLockService {
    boolean tryRunWithConversationLock(Long conversationId, Runnable runnable);
}
