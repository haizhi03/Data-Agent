package edu.zsc.ai.domain.model.dto.request.db;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeleteConstraintRequest {

    @NotNull(message = "connectionId is required")
    private Long connectionId;

    @NotBlank(message = "tableName is required")
    private String tableName;

    @NotBlank(message = "constraintName is required")
    private String constraintName;

    private String catalog;

    private String schema;
}
