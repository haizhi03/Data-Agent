package edu.zsc.ai.async;

/**
 * Lifecycle of an async task.
 *
 * <p>Cancellation semantics (M1-09 iron rule): a user cancel only ever sets
 * {@link #CANCELLING} (cancel requested). {@link #CANCELLED} is reached only when the
 * stop is confirmed — the task never started, it had nothing in flight when interrupted,
 * or the in-flight JDBC statement surfaced a vendor-confirmed cancellation. When the
 * in-flight statement's outcome cannot be confirmed (e.g. connection lost), the task
 * ends {@link #UNKNOWN} and no claim about cancellation or rollback is made.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    /** Cancel requested; awaiting factual confirmation from the executing work. */
    CANCELLING,
    COMPLETED,
    FAILED,
    /** Stop confirmed (see class doc); nothing of this task is still executing. */
    CANCELLED,
    /** The task stopped but the in-flight statement's outcome could not be confirmed. */
    UNKNOWN
}
