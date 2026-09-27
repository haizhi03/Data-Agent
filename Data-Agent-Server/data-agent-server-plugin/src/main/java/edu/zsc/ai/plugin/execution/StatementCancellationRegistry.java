package edu.zsc.ai.plugin.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of in-flight JDBC statements, keyed by execution id.
 *
 * <p>Database-agnostic cancellation mechanism (M1-09): an executor registers its
 * active {@link Statement} under the request's execution id; a cancel requester
 * (e.g. the app-layer async task manager) calls {@link #cancel(String)} with the
 * same id, which invokes {@link Statement#cancel()} on the driver.
 *
 * <p><b>State semantics (iron rule):</b> a successful {@link #cancel(String)} call
 * only means the cancel was <em>requested</em> on the driver — it never asserts the
 * statement actually stopped. Confirmation comes from the executing thread surfacing
 * the database's cancellation error (mapped to
 * {@code StatementExecutionState.CANCELLED} by the executor). If the connection is
 * lost before confirmation, the outcome is UNKNOWN and no data-state claim is allowed.
 */
public final class StatementCancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(StatementCancellationRegistry.class);

    private static final StatementCancellationRegistry INSTANCE = new StatementCancellationRegistry();

    /** Outcome of a {@link #cancel(String)} request. */
    public enum CancelOutcome {
        /** No statement is currently registered under this id (already finished or never started). */
        NO_ACTIVE_STATEMENT,
        /** Statement.cancel() was invoked without error; execution-stop is NOT yet confirmed. */
        CANCEL_REQUESTED,
        /** Statement.cancel() itself failed; the statement may still be running. */
        CANCEL_FAILED
    }

    private final ConcurrentHashMap<String, Statement> active = new ConcurrentHashMap<>();

    private StatementCancellationRegistry() {
    }

    /** Shared process-wide registry used by executors and cancel requesters. */
    public static StatementCancellationRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Handle for a registered statement; closing it deregisters the statement.
     * Intended for try-with-resources around statement execution.
     */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** No-op registration used when the request carries no execution id. */
    private static final Registration NO_OP = () -> {
    };

    /**
     * Register {@code statement} under {@code executionId} until the returned
     * registration is closed. A null/blank execution id yields a no-op registration.
     *
     * @throws IllegalStateException if another statement is still registered under the same id
     */
    public Registration register(String executionId, Statement statement) {
        if (executionId == null || executionId.isBlank()) {
            return NO_OP;
        }
        Statement previous = active.putIfAbsent(executionId, statement);
        if (previous != null) {
            throw new IllegalStateException(
                    "A statement is already registered under execution id " + executionId);
        }
        return () -> active.remove(executionId, statement);
    }

    /**
     * Request cancellation of the statement registered under {@code executionId}.
     * Only requests the cancel — see the class-level iron rule for confirmation semantics.
     *
     * @return the factual outcome of the cancel request (never null)
     */
    public CancelOutcome cancel(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return CancelOutcome.NO_ACTIVE_STATEMENT;
        }
        Statement statement = active.get(executionId);
        if (statement == null) {
            return CancelOutcome.NO_ACTIVE_STATEMENT;
        }
        try {
            statement.cancel();
            log.debug("Cancel requested on statement for execution id {}", executionId);
            return CancelOutcome.CANCEL_REQUESTED;
        } catch (SQLException e) {
            log.warn("Statement.cancel() failed for execution id {}: sqlState={} errorCode={} {}",
                    executionId, e.getSQLState(), e.getErrorCode(), e.getMessage());
            return CancelOutcome.CANCEL_FAILED;
        }
    }

    /**
     * Whether a statement is currently registered under {@code executionId}.
     * Exposed for tests and for cancel requesters that want to distinguish
     * "nothing in flight" from "cancel never surfaced".
     */
    public boolean isActive(String executionId) {
        return executionId != null && active.containsKey(executionId);
    }
}
