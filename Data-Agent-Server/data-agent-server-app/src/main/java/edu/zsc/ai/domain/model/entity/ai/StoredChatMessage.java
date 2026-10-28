package edu.zsc.ai.domain.model.entity.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredChatMessage {

    private Long id;

    private Long conversationId;

    private String role;

    private Integer tokenCount;

    private String data;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
