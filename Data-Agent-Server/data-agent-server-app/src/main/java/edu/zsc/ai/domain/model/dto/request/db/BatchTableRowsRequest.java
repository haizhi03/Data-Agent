package edu.zsc.ai.domain.model.dto.request.db;

import edu.zsc.ai.api.model.request.BaseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * Batch row modification request. All operations execute within a single
 * database transaction: either every operation commits or none of them do.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BatchTableRowsRequest extends BaseRequest {

    @NotBlank(message = "tableName is required")
    private String tableName;

    @NotEmpty(message = "operations must not be empty")
    @Valid
    private List<BatchTableRowOperationRequest> operations;

    /** When true, ambiguous UPDATE/DELETE targets (multiple matched rows) are allowed. */
    private boolean force;
}
