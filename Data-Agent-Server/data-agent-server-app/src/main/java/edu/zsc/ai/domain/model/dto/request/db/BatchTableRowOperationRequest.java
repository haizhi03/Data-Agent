package edu.zsc.ai.domain.model.dto.request.db;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Single row operation inside a batch request.
 * type is one of INSERT / UPDATE / DELETE.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchTableRowOperationRequest {

    @NotBlank(message = "operation type is required")
    private String type;

    /** Column values for INSERT. */
    private List<TableRowValueRequest> values;

    /** SET assignments for UPDATE. */
    private List<TableRowValueRequest> setValues;

    /** WHERE matching values for UPDATE / DELETE. */
    private List<TableRowValueRequest> matchValues;
}
