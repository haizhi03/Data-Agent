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
public class AiConversation {

    private Long id;

    private Long userId;

    private String title;

    private Integer tokenCount;

    private Long defaultConnectionId;
    private String defaultCatalogName;
    private String defaultSchemaName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
