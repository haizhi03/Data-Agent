package edu.zsc.ai.async;

import edu.zsc.ai.async.AsyncTask.CancellationPhase;
import edu.zsc.ai.plugin.execution.StatementCancellationRegistry;
import edu.zsc.ai.plugin.execution.StatementCancellationRegistry.CancelOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Async task registry with factual cancellation semantics (M1-09).
 *
 * <p>{@link #cancel(String)} does two things: it interrupts the worker thread
 * ({@code Future.cancel(true)}) and, because a thread interrupt cannot break a JDBC
 * call blocked in the driver, it also triggers {@code Statement.cancel()} through the
 * {@link StatementCancellationRegistry} under the convention
 * <b>task id == execution id</b> — SQL executions submitted as async tasks must set
 * {@code SqlCommandRequest.executionId} to the task id.
 *
 * <p>Status iron rule: cancel() only moves a task to {@link TaskStatus#CANCELLING}
 * (cancel requested). {@link TaskStatus#CANCELLED} requires confirmation; when the
 * in-flight statement's outcome cannot be confirmed the task ends
 * {@link TaskStatus#UNKNOWN}. The executing code reports the JDBC outcome back via
 * {@link #confirmCancellation(String)} (statement state CANCELLED observed) or
 * {@link #markCancellationUncertain(String)} (connection lost / outcome undecidable).
 */
@Slf4j
@Component
public class AsyncTaskManager {

    private static final long RETENTION_SECONDS = 30 * 60;

    private final ConcurrentHashMap<String, AsyncTask<?>> tasks = new ConcurrentHashMap<>();


    public <T> AsyncTask<T> submit(String taskId, Callable<T> callable,
                                   ThreadPoolTaskExecutor executor) {
        AsyncTask<T> record = new AsyncTask<>(taskId);
        tasks.put(taskId, record);

        Future<?> future = executor.submit(() -> {
            record.started = true;
            if (record.cancellation == CancellationPhase.NONE) {
                record.status = TaskStatus.RUNNING;
            }
            try {
                record.result = callable.call();
                record.status = switch (record.cancellation) {
                    // cancel confirmed: the recorded result reflects a cancelled statement
                    case CONFIRMED -> TaskStatus.CANCELLED;
                    case UNCERTAIN -> TaskStatus.UNKNOWN;
                    // no cancel, or the cancel request lost the race: the work factually completed
                    default -> TaskStatus.COMPLETED;
                };
                log.debug("Async task completed: taskId={}, status={}", taskId, record.status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                record.status = interruptedStatus(record);
                log.debug("Async task interrupted: taskId={}, status={}", taskId, record.status);
            } catch (Exception e) {
                if (record.cancellation == CancellationPhase.CONFIRMED) {
                    record.status = TaskStatus.CANCELLED;
                } else if (record.cancellation == CancellationPhase.UNCERTAIN) {
                    record.status = TaskStatus.UNKNOWN;
                } else {
                    record.errorMessage = e.getMessage();
                    record.status = TaskStatus.FAILED;
                    log.warn("Async task failed: taskId={}, error={}", taskId, e.getMessage());
                }
            }
        });
        record.future = future;
        return record;
    }


    @SuppressWarnings("unchecked")
    public <T> Optional<AsyncTask<T>> get(String taskId) {
        return Optional.ofNullable((AsyncTask<T>) tasks.get(taskId));
    }


    /**
     * Request cancellation of a task. Returns true when the request was accepted —
     * never asserts the task has stopped. Triggers JDBC Statement.cancel() for the
     * statement currently executed under this id, then interrupts the worker thread.
     */
    public boolean cancel(String taskId) {
        AsyncTask<?> record = tasks.get(taskId);
        if (record == null || isTerminal(record.status)) {
            return false;
        }
        record.cancellation = CancellationPhase.REQUESTED;
        record.status = TaskStatus.CANCELLING;

        record.cancelOutcome = StatementCancellationRegistry.getInstance().cancel(taskId);
        log.debug("Cancel requested: taskId={}, statementCancelOutcome={}", taskId, record.cancelOutcome);

        if (record.future != null) {
            record.future.cancel(true);
            if (!record.started && record.future.isCancelled()) {
                // never started: nothing was ever in flight, the stop is factual
                record.status = TaskStatus.CANCELLED;
            }
        }
        return true;
    }

    /**
     * Report that the in-flight statement surfaced a vendor-confirmed cancellation
     * (executor observed statementState CANCELLED). Upgrades the task to a confirmed
     * cancel; also corrects a prematurely recorded UNKNOWN.
     *
     * @return true when the confirmation was applied to a task awaiting it
     */
    public boolean confirmCancellation(String taskId) {
        AsyncTask<?> record = tasks.get(taskId);
        if (record == null || record.cancellation != CancellationPhase.REQUESTED) {
            return false;
        }
        record.cancellation = CancellationPhase.CONFIRMED;
        if (record.status == TaskStatus.UNKNOWN) {
            record.status = TaskStatus.CANCELLED;
        }
        return true;
    }

    /**
     * Report that the in-flight statement's outcome can no longer be confirmed (e.g.
     * the connection was lost after the cancel request). The task must end UNKNOWN —
     * never CANCELLED — and no transaction rollback may be claimed.
     *
     * @return true when the marker was applied to a task awaiting confirmation
     */
    public boolean markCancellationUncertain(String taskId) {
        AsyncTask<?> record = tasks.get(taskId);
        if (record == null || record.cancellation != CancellationPhase.REQUESTED) {
            return false;
        }
        record.cancellation = CancellationPhase.UNCERTAIN;
        if (record.status == TaskStatus.CANCELLED
                && (record.cancelOutcome == CancelOutcome.CANCEL_REQUESTED
                || record.cancelOutcome == CancelOutcome.CANCEL_FAILED)) {
            // a statement was in flight; the earlier interrupt-based CANCELLED was premature
            record.status = TaskStatus.UNKNOWN;
        }
        return true;
    }

    /**
     * Terminal status after the worker was interrupted. CANCELLED only when the cancel
     * is confirmed or nothing was in flight; UNKNOWN whenever a statement was in flight
     * (or the cancel could not be delivered) and its outcome never surfaced.
     */
    private TaskStatus interruptedStatus(AsyncTask<?> record) {
        if (record.cancellation == CancellationPhase.CONFIRMED) {
            return TaskStatus.CANCELLED;
        }
        if (record.cancellation == CancellationPhase.UNCERTAIN) {
            return TaskStatus.UNKNOWN;
        }
        if (record.cancelOutcome == CancelOutcome.CANCEL_REQUESTED
                || record.cancelOutcome == CancelOutcome.CANCEL_FAILED) {
            return TaskStatus.UNKNOWN;
        }
        return TaskStatus.CANCELLED;
    }

    @Scheduled(fixedDelay = 600_000)
    void cleanup() {
        long cutoff = Instant.now().getEpochSecond() - RETENTION_SECONDS;
        int removed = 0;
        var iter = tasks.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (isTerminal(entry.getValue().status) && entry.getValue().createdAt < cutoff) {
                iter.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Cleaned up {} expired async tasks", removed);
        }
    }

    public static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED
                || status == TaskStatus.UNKNOWN;
    }
}
