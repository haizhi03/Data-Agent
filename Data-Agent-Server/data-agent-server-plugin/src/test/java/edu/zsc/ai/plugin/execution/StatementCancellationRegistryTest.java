package edu.zsc.ai.plugin.execution;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Contract of {@link StatementCancellationRegistry}: cancel only ever reports the
 * factual outcome of the cancel REQUEST — never that the statement stopped.
 */
class StatementCancellationRegistryTest {

    private final StatementCancellationRegistry registry = StatementCancellationRegistry.getInstance();

    private static String uniqueId() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void cancelWithoutRegisteredStatementReportsNoActiveStatement() {
        assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT,
                registry.cancel(uniqueId()));
        assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT,
                registry.cancel(null));
        assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT,
                registry.cancel("  "));
    }

    @Test
    void cancelRegisteredStatementInvokesDriverCancel() throws Exception {
        String id = uniqueId();
        Statement statement = mock(Statement.class);

        try (StatementCancellationRegistry.Registration ignored = registry.register(id, statement)) {
            assertTrue(registry.isActive(id));
            assertEquals(StatementCancellationRegistry.CancelOutcome.CANCEL_REQUESTED, registry.cancel(id));
            verify(statement).cancel();
        }
        // deregistered: further cancels find nothing
        assertFalse(registry.isActive(id));
        assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT, registry.cancel(id));
    }

    @Test
    void driverCancelFailureIsReportedAsCancelFailed() throws Exception {
        String id = uniqueId();
        Statement statement = mock(Statement.class);
        doThrow(new SQLException("cancel unsupported", "0A000", 123)).when(statement).cancel();

        try (StatementCancellationRegistry.Registration ignored = registry.register(id, statement)) {
            assertEquals(StatementCancellationRegistry.CancelOutcome.CANCEL_FAILED, registry.cancel(id));
        }
    }

    @Test
    void duplicateRegistrationUnderSameIdIsRejected() {
        String id = uniqueId();
        Statement first = mock(Statement.class);
        Statement second = mock(Statement.class);

        try (StatementCancellationRegistry.Registration ignored = registry.register(id, first)) {
            assertThrows(IllegalStateException.class, () -> registry.register(id, second));
        }
    }

    @Test
    void blankExecutionIdYieldsNoOpRegistration() throws Exception {
        Statement statement = mock(Statement.class);
        try (StatementCancellationRegistry.Registration ignored = registry.register(null, statement)) {
            assertFalse(registry.isActive(null));
        }
    }
}
