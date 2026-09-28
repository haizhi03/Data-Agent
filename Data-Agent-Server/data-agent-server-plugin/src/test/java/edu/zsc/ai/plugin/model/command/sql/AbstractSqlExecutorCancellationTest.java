package edu.zsc.ai.plugin.model.command.sql;

import edu.zsc.ai.plugin.execution.StatementCancellationRegistry;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import edu.zsc.ai.plugin.value.JdbcValueContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M1-09 exit semantics of {@link AbstractSqlExecutor} for cancellation, connection
 * loss, statement registration and the large-result-set row cap.
 */
class AbstractSqlExecutorCancellationTest {

    /** Executor whose cancellation hook confirms vendor code -6515 (DM's confirmed cancel). */
    private static final class CancellableExecutor extends AbstractSqlExecutor {
        @Override
        protected Object getJdbcValue(JdbcValueContext context) {
            return null;
        }

        @Override
        protected boolean isCancellationException(SQLException e) {
            return e.getErrorCode() == -6515;
        }
    }

    private static final CancellableExecutor EXECUTOR = new CancellableExecutor();

    @Test
    void confirmedCancellationIsCancelledAndRolledBack() throws Exception {
        Connection connection = failingConnection(new SQLException("操作被取消", "25000", -6515));
        when(connection.getAutoCommit()).thenReturn(true);

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.CANCELLED, result.getStatementState());
        assertEquals(TransactionOutcome.ROLLED_BACK, result.getTransactionOutcome());
        assertEquals(-6515, result.getErrorCode());
        verify(connection).rollback();
    }

    @Test
    void cancellationHookWinsOverTimeoutClassification() throws Exception {
        // sqlState 57014 matches the default timeout hook; the vendor cancel code must win
        Connection connection = failingConnection(new SQLException("cancelled", "57014", -6515));

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, false, null));

        assertEquals(StatementExecutionState.CANCELLED, result.getStatementState());
    }

    @Test
    void connectionLossIsUnknownAndNeverClaimsRollback() throws Exception {
        Connection connection = failingConnection(new SQLException("网络通信异常", "08S01", 6001));
        when(connection.getAutoCommit()).thenReturn(true);

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.UNKNOWN, result.getStatementState());
        assertEquals(TransactionOutcome.UNKNOWN, result.getTransactionOutcome());
        verify(connection, never()).rollback();
    }

    @Test
    void connectionLossWithoutTransactionIsUnknownWithoutOutcome() throws Exception {
        Connection connection = failingConnection(new SQLException("connection reset", "08006", 0));

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, false, null));

        assertEquals(StatementExecutionState.UNKNOWN, result.getStatementState());
        assertEquals(TransactionOutcome.NONE, result.getTransactionOutcome());
    }

    @Test
    void statementIsRegisteredUnderExecutionIdDuringExecution() throws Exception {
        String executionId = "exec-" + UUID.randomUUID();
        StatementCancellationRegistry registry = StatementCancellationRegistry.getInstance();

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        AtomicInteger cancelObserved = new AtomicInteger();
        when(statement.execute(anyString())).thenAnswer(invocation -> {
            // while executing, the statement must be reachable for cancellation
            assertTrue(registry.isActive(executionId));
            cancelObserved.set(registry.cancel(executionId).ordinal());
            return false;
        });
        when(statement.getUpdateCount()).thenReturn(-1);

        SqlCommandRequest request = request(connection, false, null);
        request.setExecutionId(executionId);
        SqlCommandResult result = EXECUTOR.executeCommand(request);

        assertTrue(result.isSuccess());
        assertEquals(StatementCancellationRegistry.CancelOutcome.CANCEL_REQUESTED.ordinal(),
                cancelObserved.get());
        verify(statement).cancel();
        // registration is released once execution finishes
        assertFalse(registry.isActive(executionId));
    }

    @Test
    void requestMaxRowsCapsRowsAndMarksTruncated() throws Exception {
        Connection connection = streamingConnection(Integer.MAX_VALUE);

        SqlCommandRequest request = request(connection, false, null);
        request.setMaxRows(100);
        SqlCommandResult result = EXECUTOR.executeCommand(request);

        assertTrue(result.isSuccess());
        assertEquals(100, result.getRows().size());
        assertEquals(100, result.getFetchRows());
        assertEquals(Boolean.TRUE, result.getTruncated());
        assertEquals(Boolean.TRUE, result.getLimitApplied());
    }

    @Test
    void defaultRowCapBoundsMemoryOnUnboundedResultSet() throws Exception {
        Connection connection = streamingConnection(Integer.MAX_VALUE);

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, false, null));

        assertTrue(result.isSuccess());
        assertEquals(10_000, result.getRows().size());
        assertEquals(Boolean.TRUE, result.getTruncated());
    }

    @Test
    void nonTruncatedResultBelowCapIsMarkedNotTruncated() throws Exception {
        Connection connection = streamingConnection(42);

        SqlCommandRequest request = request(connection, false, null);
        request.setMaxRows(100);
        SqlCommandResult result = EXECUTOR.executeCommand(request);

        assertEquals(42, result.getRows().size());
        assertEquals(Boolean.FALSE, result.getTruncated());
        assertEquals(Boolean.FALSE, result.getLimitApplied());
    }

    @Test
    void zeroMaxRowsDisablesTheCap() throws Exception {
        Connection connection = streamingConnection(1000);

        SqlCommandRequest request = request(connection, false, null);
        request.setMaxRows(0);
        SqlCommandResult result = EXECUTOR.executeCommand(request);

        assertEquals(1000, result.getRows().size());
        assertEquals(Boolean.FALSE, result.getTruncated());
    }

    @Test
    void fetchSizeHintIsAppliedToStatement() throws Exception {
        Connection connection = streamingConnection(1);
        Statement statement = connection.createStatement();

        SqlCommandRequest request = request(connection, false, null);
        request.setFetchSize(500);
        EXECUTOR.executeCommand(request);

        verify(statement).setFetchSize(500);
    }

    private Connection failingConnection(SQLException failure) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(failure);
        return connection;
    }

    /**
     * A connection streaming a one-column result set of {@code totalRows} rows
     * (Integer.MAX_VALUE = effectively infinite).
     */
    private Connection streamingConnection(int totalRows) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(statement.getMoreResults()).thenReturn(false);
        when(statement.getUpdateCount()).thenReturn(-1);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnName(1)).thenReturn("C");
        when(metaData.getColumnLabel(1)).thenReturn("C");
        when(metaData.getColumnTypeName(1)).thenReturn("INT");
        when(metaData.getColumnType(1)).thenReturn(Types.INTEGER);
        AtomicInteger produced = new AtomicInteger();
        when(resultSet.next()).thenAnswer(invocation -> produced.incrementAndGet() <= totalRows);
        return connection;
    }

    private SqlCommandRequest request(Connection connection, boolean needTransaction, Integer timeoutMs) {
        SqlCommandRequest request = new SqlCommandRequest();
        request.setConnection(connection);
        request.setOriginalSql("SELECT C FROM T");
        request.setExecuteSql("SELECT C FROM T");
        request.setNeedTransaction(needTransaction);
        request.setTimeoutMs(timeoutMs);
        return request;
    }
}
