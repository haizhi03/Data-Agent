package edu.zsc.ai.plugin.model.transaction;

/**
 * Execution state of a single SQL statement.
 *
 * <p>Semantics are factual: the state only asserts what is actually known.
 * In particular {@link #UNKNOWN} forbids any claim about the data state.
 */
public enum StatementExecutionState {
    /** Not submitted to the DB (rejected up front, or skipped after a prior batch failure). */
    NOT_EXECUTED,
    /** Executed; transaction not finished yet (intermediate state inside a batch with autoCommit=false). */
    EXECUTED,
    /** Effect persisted (statement-level or batch-level commit confirmed). */
    COMMITTED,
    /** Execution failed; the statement itself left no residual effect (DB rejected it). */
    FAILED,
    /** Executed but its effect was rolled back with the transaction. */
    ROLLED_BACK,
    /** Statement-level outcome undecidable (e.g. multi-result statement failed midway). */
    PARTIALLY_APPLIED,
    /** Query/execution timed out. Does not by itself assert any transaction state. */
    TIMED_OUT,
    /** Actively cancelled. */
    CANCELLED,
    /** Commit/rollback outcome could not be confirmed; no data-state claim is allowed. */
    UNKNOWN
}
