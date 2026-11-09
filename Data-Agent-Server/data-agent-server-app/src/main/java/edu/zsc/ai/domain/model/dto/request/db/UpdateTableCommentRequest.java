package edu.zsc.ai.domain.model.dto.request.db;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTableCommentRequest {

    @NotNull(message = "connectionId is required")
    private Long connectionId;

    @NotBlank(message = "tableName is required")
    private String tableName;

    /** Empty string clears the table comment. */
    @NotNull(message = "comment is required")
    private String comment;

    private String catalog;

    private String schema;
}
