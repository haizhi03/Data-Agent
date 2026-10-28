package edu.zsc.ai.domain.model.entity.ai;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConversationMemoryCursor {

    private Long id;

    private Long userId;

    private Long conversationId;

    private Long lastProcessedMessageId;

    private LocalDateTime lastProcessedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
