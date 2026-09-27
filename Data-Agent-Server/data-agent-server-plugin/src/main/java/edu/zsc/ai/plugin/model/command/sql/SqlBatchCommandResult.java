package edu.zsc.ai.plugin.model.command.sql;

import edu.zsc.ai.plugin.model.command.base.BaseCommandResult;
import edu.zsc.ai.plugin.model.transaction.BatchExecutionState;
import edu.zsc.ai.plugin.model.transaction.BatchMode;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aggregate result of a batch SQL execution.
 *
 * <p>{@link #results} has the same length and order as the request; statements that were
 * never submitted carry {@code statementState=NOT_EXECUTED}. Historical per-statement
 * facts (success flag, original error message) are never rewritten — batch semantics are
 * expressed only through {@link #batchState} and per-statement statementState.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
public class SqlBatchCommandResult extends BaseCommandResult {

    private BatchExecutionState batchState;

    private BatchMode mode;

    /** Batch-level commit/rollback outcome. */
    private TransactionOutcome transactionOutcome;

    /** Index of the first failed statement, null when nothing failed. */
    private Integer failedIndex;

    /** One result per requested statement, in request order. */
    private List<SqlCommandResult> results;
}
