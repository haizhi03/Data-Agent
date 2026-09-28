package edu.zsc.ai.async;

import edu.zsc.ai.plugin.execution.StatementCancellationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * M1-09 cancellation contract of {@link AsyncTaskManager}: cancel only requests;
 * CANCELLED requires confirmation; an unconfirmed in-flight statement ends UNKNOWN.
 */
class AsyncTaskManagerCancellationTest {

    private final AsyncTaskManager manager = new AsyncTaskManager();
    private final StatementCancellationRegistry registry = StatementCancellationRegistry.getInstance();
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("test-async-");
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void cancelPropagatesToRegisteredStatementAndEndsUnknownUntilConfirmed() throws Exception {
        String taskId = uniqueId();
        Statement statement = mock(Statement.class);
        CountDownLatch block = new CountDownLatch(1);

        manager.submit(taskId, () -> {
            try (StatementCancellationRegistry.Registration ignored = registry.register(taskId, statement)) {
                block.await();
                return "done";
            }
        }, executor);
        awaitTrue(() -> registry.isActive(taskId));

        assertTrue(manager.cancel(taskId));
        // Statement.cancel was triggered for the in-flight statement
        verify(statement).cancel();

        awaitTrue(() -> isDone(taskId));
        // the in-flight statement's outcome never surfaced: UNKNOWN, not CANCELLED
        assertEquals(TaskStatus.UNKNOWN, manager.get(taskId).orElseThrow().status);
        assertEquals(AsyncTask.CancellationPhase.REQUESTED,
                manager.get(taskId).orElseThrow().cancellation);
    }

    @Test
    void confirmedCancellationEndsCancelled() throws Exception {
        String taskId = uniqueId();
        Statement statement = mock(Statement.class);
        CountDownLatch block = new CountDownLatch(1);

        manager.submit(taskId, () -> {
            try (StatementCancellationRegistry.Registration ignored = registry.register(taskId, statement)) {
                block.await();
                return "done";
            } catch (InterruptedException interrupted) {
                // the execution layer observed the JDBC statement report a confirmed cancel
                manager.confirmCancellation(taskId);
                throw interrupted;
            }
        }, executor);
        awaitTrue(() -> registry.isActive(taskId));

        assertTrue(manager.cancel(taskId));
        awaitTrue(() -> isDone(taskId));

        assertEquals(TaskStatus.CANCELLED, manager.get(taskId).orElseThrow().status);
        assertEquals(AsyncTask.CancellationPhase.CONFIRMED,
                manager.get(taskId).orElseThrow().cancellation);
        verify(statement).cancel();
    }

    @Test
    void normalReturnAfterConfirmedCancelIsCancelledNotCompleted() throws Exception {
        String taskId = uniqueId();
        Statement statement = mock(Statement.class);

        manager.submit(taskId, () -> {
            try (StatementCancellationRegistry.Registration ignored = registry.register(taskId, statement)) {
                // simulate JDBC work that ignores the interrupt and returns after the
                // statement surfaced a confirmed cancellation
                try {
                    awaitTrue(() -> manager.get(taskId).orElseThrow().cancelOutcome != null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // a worker thread may see the interrupt before the driver surfaces
                    // the cancel error; the confirmation below is what counts
                }
                manager.confirmCancellation(taskId);
                return "partial-result";
            }
        }, executor);
        awaitTrue(() -> registry.isActive(taskId));

        assertTrue(manager.cancel(taskId));
        awaitTrue(() -> isDone(taskId));

        AsyncTask<Object> task = manager.get(taskId).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, task.status);
        assertEquals("partial-result", task.result);
    }

    @Test
    void cancelWithNothingInFlightEndsCancelled() throws Exception {
        String taskId = uniqueId();
        CountDownLatch block = new CountDownLatch(1);

        manager.submit(taskId, () -> {
            block.await();
            return "done";
        }, executor);
        awaitTrue(() -> manager.get(taskId).orElseThrow().status == TaskStatus.RUNNING);

        assertTrue(manager.cancel(taskId));
        awaitTrue(() -> isDone(taskId));

        assertEquals(TaskStatus.CANCELLED, manager.get(taskId).orElseThrow().status);
        assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT,
                manager.get(taskId).orElseThrow().cancelOutcome);
    }

    @Test
    void uncertainCancellationNeverEndsCancelled() throws Exception {
        String taskId = uniqueId();
        Statement statement = mock(Statement.class);
        CountDownLatch block = new CountDownLatch(1);

        manager.submit(taskId, () -> {
            try (StatementCancellationRegistry.Registration ignored = registry.register(taskId, statement)) {
                block.await();
                return "done";
            } catch (InterruptedException interrupted) {
                // connection lost after the cancel request: outcome undecidable
                manager.markCancellationUncertain(taskId);
                throw interrupted;
            }
        }, executor);
        awaitTrue(() -> registry.isActive(taskId));

        assertTrue(manager.cancel(taskId));
        awaitTrue(() -> isDone(taskId));

        assertEquals(TaskStatus.UNKNOWN, manager.get(taskId).orElseThrow().status);
        assertEquals(AsyncTask.CancellationPhase.UNCERTAIN,
                manager.get(taskId).orElseThrow().cancellation);
    }

    @Test
    void cancelNeverStartedTaskIsFactuallyCancelled() throws Exception {
        ThreadPoolTaskExecutor single = new ThreadPoolTaskExecutor();
        single.setCorePoolSize(1);
        single.setMaxPoolSize(1);
        single.setQueueCapacity(10);
        single.initialize();
        try {
            CountDownLatch block = new CountDownLatch(1);
            manager.submit(uniqueId(), () -> {
                block.await();
                return null;
            }, single);

            String queuedId = uniqueId();
            AtomicBoolean ran = new AtomicBoolean();
            manager.submit(queuedId, () -> {
                ran.set(true);
                return null;
            }, single);

            assertTrue(manager.cancel(queuedId));
            assertEquals(TaskStatus.CANCELLED, manager.get(queuedId).orElseThrow().status);
            block.countDown();
            awaitTrue(() -> single.getThreadPoolExecutor().getQueue().isEmpty()
                    && single.getThreadPoolExecutor().getActiveCount() == 0);
            assertFalse(ran.get(), "a task cancelled while queued must never run");
        } finally {
            single.shutdown();
        }
    }

    @Test
    void cancelTerminalOrUnknownTaskIsRejected() throws Exception {
        assertFalse(manager.cancel("no-such-task"));

        String taskId = uniqueId();
        manager.submit(taskId, () -> "done", executor);
        awaitTrue(() -> manager.get(taskId).orElseThrow().status == TaskStatus.COMPLETED);

        assertFalse(manager.cancel(taskId));
        assertEquals(TaskStatus.COMPLETED, manager.get(taskId).orElseThrow().status);
    }

    @Test
    void confirmationCallbacksAreRejectedWithoutPendingRequest() throws Exception {
        String taskId = uniqueId();
        manager.submit(taskId, () -> "done", executor);
        awaitTrue(() -> manager.get(taskId).orElseThrow().status == TaskStatus.COMPLETED);

        assertFalse(manager.confirmCancellation(taskId));
        assertFalse(manager.markCancellationUncertain(taskId));
        assertFalse(manager.confirmCancellation("no-such-task"));
    }

    private boolean isDone(String taskId) {
        return AsyncTaskManager.isTerminal(manager.get(taskId).orElseThrow().status);
    }

    private static String uniqueId() {
        return "task-" + UUID.randomUUID();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(10);
        }
    }
}
