package edu.zsc.ai.plugin.model.transaction;

/**
 * Aggregate state of a batch SQL execution.
 */
public enum BatchExecutionState {
    /** All statements COMMITTED. */
    SUCCESS,
    /** At least one statement FAILED and the batch was not committed. */
    FAILED,
    /** ATOMIC batch confirmed rolled back as a whole. */
    ROLLED_BACK,
    /** STEPWISE batch with only some statements COMMITTED. */
    PARTIAL,
    /** Commit/rollback outcome not confirmable (e.g. commit failed, or implicit-commit DDL was involved). */
    UNKNOWN
}
