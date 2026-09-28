package edu.zsc.ai.domain.service.db.support;

import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.connection.ConnectionFailureException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionManagerChainTest {

    @Test
    void fromManagers_rejectsEmptyManagerList() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionManagerChain.fromManagers(List.of(), (m, c) -> null));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionManagerChain.fromManagers(null, (m, c) -> null));
    }

    @Test
    void handle_shortCircuitsOnFirstSuccess() {
        ConnectionManager first = mock(ConnectionManager.class);
        ConnectionManager second = mock(ConnectionManager.class);
        Connection connection = mock(Connection.class);
        when(first.connect(any())).thenReturn(connection);

        ConnectionManagerChain.ConnectionManagerHandleResult<Connection> result =
                ConnectionManagerChain.fromManagers(List.of(first, second), ConnectionManager::connect)
                        .handle(new ConnectionConfig());

        assertSame(first, result.manager());
        assertSame(connection, result.result());
        verify(second, never()).connect(any());
    }

    @Test
    void handle_aggregatesAllFailureReasonsWhenEveryManagerFails() {
        ConnectionManager first = mock(ConnectionManager.class);
        ConnectionManager second = mock(ConnectionManager.class);
        RuntimeException networkFailure = new RuntimeException("network unreachable");
        ConnectionFailureException authFailure = new ConnectionFailureException(
                ConnectionFailureException.Category.AUTHENTICATION, "28000", 1045, "access denied", null);
        when(first.connect(any())).thenThrow(networkFailure);
        when(second.connect(any())).thenThrow(authFailure);

        ConnectionFailureException aggregate = assertThrows(ConnectionFailureException.class, () ->
                ConnectionManagerChain.fromManagers(List.of(first, second), ConnectionManager::connect)
                        .handle(new ConnectionConfig()));

        assertEquals(ConnectionFailureException.Category.UNKNOWN, aggregate.getCategory());
        assertEquals("All 2 connection manager attempt(s) failed", aggregate.getMessage());
        assertSame(networkFailure, aggregate.getCause());
        assertEquals(2, aggregate.getSuppressed().length);
        assertSame(networkFailure, aggregate.getSuppressed()[0]);
        assertSame(authFailure, aggregate.getSuppressed()[1]);
        // each attempt's classification survives aggregation
        assertEquals(ConnectionFailureException.Category.AUTHENTICATION,
                assertInstanceOf(ConnectionFailureException.class, aggregate.getSuppressed()[1]).getCategory());
    }

    @Test
    void handle_skipsFailingManagerAndReturnsLaterSuccess() {
        ConnectionManager first = mock(ConnectionManager.class);
        ConnectionManager second = mock(ConnectionManager.class);
        Connection connection = mock(Connection.class);
        when(first.connect(any())).thenThrow(new RuntimeException("driver load failed"));
        when(second.connect(any())).thenReturn(connection);

        ConnectionManagerChain.ConnectionManagerHandleResult<Connection> result =
                ConnectionManagerChain.fromManagers(List.of(first, second), ConnectionManager::connect)
                        .handle(new ConnectionConfig());

        assertSame(second, result.manager());
        assertSame(connection, result.result());
    }
}
