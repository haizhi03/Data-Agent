package edu.zsc.ai.plugin.model.transaction;

/**
 * Batch transaction semantics.
 */
public enum BatchMode {
    /** Whole batch in one transaction; any failure rolls the batch back. */
    ATOMIC,
    /** Each statement commits independently; execution continues after failures. */
    STEPWISE
}
