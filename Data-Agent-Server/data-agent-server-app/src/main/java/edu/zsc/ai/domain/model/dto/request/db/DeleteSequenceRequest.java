package edu.zsc.ai.domain.model.dto.request.db;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeleteSequenceRequest {

    @NotNull(message = "connectionId is required")
    private Long connectionId;

    @NotBlank(message = "sequenceName is required")
    private String sequenceName;

    private String catalog;

    private String schema;
}
