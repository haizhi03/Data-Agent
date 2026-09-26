package edu.zsc.ai.domain.model.dto.response.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of executing a batch of row operations within one transaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchTableRowsResponse {

    /** True only when every operation succeeded and the transaction committed. */
    private boolean success;

    /** Whether the transaction was committed. */
    private boolean committed;

    /** True when failure was caused by an ambiguous target requiring force=true. */
    private boolean requiresForce;

    /** Structured code: UPDATE_REQUIRES_FORCE / DELETE_REQUIRES_FORCE. */
    private String forceCode;

    private int total;

    private int succeeded;

    /** Zero-based index of the first failed operation, null when all succeeded. */
    private Integer failedAtIndex;

    private String errorMessage;

    /** Per-operation results, in request order. */
    private List<ItemResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemResult {
        private String type;
        private boolean success;
        private int affectedRows;
        private String errorCode;
        private String errorMessage;
    }
}
