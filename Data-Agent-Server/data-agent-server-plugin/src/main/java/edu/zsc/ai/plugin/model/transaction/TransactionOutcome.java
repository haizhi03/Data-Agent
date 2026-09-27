package edu.zsc.ai.plugin.model.transaction;

/**
 * Outcome of the transaction surrounding a statement or batch.
 */
public enum TransactionOutcome {
    /** No transaction was used. */
    NONE,
    /** Commit confirmed. */
    COMMITTED,
    /** Rollback confirmed. */
    ROLLED_BACK,
    /** Commit/rollback result could not be confirmed; no data-state claim is allowed. */
    UNKNOWN
}
