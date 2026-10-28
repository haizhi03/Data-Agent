package edu.zsc.ai.domain.service.ai;

import edu.zsc.ai.domain.model.entity.ai.AiConversationMemoryCursor;

public interface AiConversationMemoryCursorService {
    AiConversationMemoryCursor findByConversationId(Long conversationId);
    void save(AiConversationMemoryCursor cursor);
    void updateById(AiConversationMemoryCursor cursor);
}
