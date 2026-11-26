package edu.zsc.ai.api.model.request;

import edu.zsc.ai.plugin.model.transaction.BatchExecutionState;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** User-selected workbench evidence, interpreted as a past execution snapshot. */
public record ChatExecutionReference(
        @NotBlank @Size(max=128) String executionId,
        @Min(0) int statementIndex,
        @NotBlank @Size(max=8000) String sql,
        @NotNull ChatDefaultTarget target,
        @Size(max=512) String documentId,
        @Size(max=256) String documentName,
        @Min(0) Integer documentVersion,
        boolean truncated,
        @NotNull @Valid Result result) {
    public record Result(
            boolean success,
            @NotNull StatementExecutionState statementState,
            @NotNull TransactionOutcome transactionOutcome,
            BatchExecutionState batchState,
            @Size(max=2000) String errorMessage,
            @Size(max=16) String sqlState,
            @NotNull @Size(max=8) List<@Size(max=64) String> headers,
            @NotNull @Size(max=5) List<@NotNull @Size(max=8) List<@Size(max=128) String>> rows,
            @Min(0) long returnedRows,
            Long affectedRows) { }
}
