package edu.zsc.ai.domain.model.dto.request.db;

import edu.zsc.ai.plugin.model.structure.TableStructure;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TableStructureDraftRequest {

    @NotNull(message = "connectionId is required")
    private Long connectionId;

    private String catalog;

    private String schema;

    @NotNull(message = "baseline is required")
    private TableStructure baseline;

    @NotNull(message = "draft is required")
    private TableStructure draft;
}
