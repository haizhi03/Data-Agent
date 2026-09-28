package edu.zsc.ai.async;

import edu.zsc.ai.plugin.execution.StatementCancellationRegistry;
import lombok.Data;

import java.time.Instant;
import java.util.concurrent.Future;


@Data
public class AsyncTask<T> {

    /**
     * Confirmation phase of a requested cancellation. Distinct from the task status:
     * the status says where the task is, the phase says what is factually known about
     * the in-flight JDBC statement's cancel.
     */
    public enum CancellationPhase {
        /** No cancel was requested. */
        NONE,
        /** Cancel requested, not yet confirmed by the executing statement. */
        REQUESTED,
        /** The in-flight statement surfaced a vendor-confirmed cancellation. */
        CONFIRMED,
        /** The in-flight statement's outcome can no longer be confirmed (e.g. connection lost). */
        UNCERTAIN
    }

    private final String id;
    final long createdAt;

    volatile TaskStatus status = TaskStatus.PENDING;
    volatile T result;
    volatile String errorMessage;
    volatile Future<?> future;

    /** Set by the worker thread the moment it starts running the callable. */
    volatile boolean started;
    /** Cancellation confirmation phase; see {@link CancellationPhase}. */
    volatile CancellationPhase cancellation = CancellationPhase.NONE;
    /** Factual outcome of the last Statement.cancel attempt, if any was dispatched. */
    volatile StatementCancellationRegistry.CancelOutcome cancelOutcome;

    AsyncTask(String id) {
        this.id = id;
        this.createdAt = Instant.now().getEpochSecond();
    }
}
