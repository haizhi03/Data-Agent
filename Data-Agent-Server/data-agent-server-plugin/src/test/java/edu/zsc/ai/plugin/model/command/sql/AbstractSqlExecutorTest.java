package edu.zsc.ai.plugin.model.command.sql;

import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import edu.zsc.ai.plugin.value.JdbcValueContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exit-semantics contract of {@link AbstractSqlExecutor}: every path must fill
 * statementState/transactionOutcome factually (M1-06 / M1-09).
 */
class AbstractSqlExecutorTest {

    private static final TestExecutor EXECUTOR = new TestExecutor();

    @Test
    void successWithTransactionIsCommitted() throws Exception {
        Connection connection = executableConnection();
        when(connection.getAutoCommit()).thenReturn(true);

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertTrue(result.isSuccess());
        assertEquals(StatementExecutionState.COMMITTED, result.getStatementState());
        assertEquals(TransactionOutcome.COMMITTED, result.getTransactionOutcome());
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void successWithoutTransactionIsExecuted() throws Exception {
        Connection connection = executableConnection();

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, false, null));

        assertTrue(result.isSuccess());
        assertEquals(StatementExecutionState.EXECUTED, result.getStatementState());
        assertEquals(TransactionOutcome.NONE, result.getTransactionOutcome());
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void executeFailureWithTransactionRollsBack() throws Exception {
        Connection connection = failingConnection(new SQLException("boom", "42000", 100));
        when(connection.getAutoCommit()).thenReturn(true);

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.FAILED, result.getStatementState());
        assertEquals(TransactionOutcome.ROLLED_BACK, result.getTransactionOutcome());
        assertEquals("42000", result.getSqlState());
        assertEquals(100, result.getErrorCode());
        assertTrue(result.getErrorMessage().contains("boom"));
        verify(connection).rollback();
    }

    @Test
    void executeFailureWithoutTransactionHasNoOutcome() throws Exception {
        Connection connection = failingConnection(new SQLException("boom", "42000", 100));

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, false, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.FAILED, result.getStatementState());
        assertEquals(TransactionOutcome.NONE, result.getTransactionOutcome());
        verify(connection, never()).rollback();
    }

    @Test
    void timeoutExceptionIsTimedOutAndRolledBack() throws Exception {
        Connection connection = failingConnection(new SQLTimeoutException("timeout", "57014"));
        when(connection.getAutoCommit()).thenReturn(true);

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.TIMED_OUT, result.getStatementState());
        assertEquals(TransactionOutcome.ROLLED_BACK, result.getTransactionOutcome());
        verify(connection).rollback();
    }

    @Test
    void rollbackFailureAfterExecuteFailureIsUnknown() throws Exception {
        Connection connection = failingConnection(new SQLException("boom"));
        doThrow(new SQLException("rollback failed")).when(connection).rollback();

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.FAILED, result.getStatementState());
        assertEquals(TransactionOutcome.UNKNOWN, result.getTransactionOutcome());
    }

    @Test
    void commitFailureThenSuccessfulRollbackIsRolledBack() throws Exception {
        Connection connection = executableConnection();
        doThrow(new SQLException("commit failed", "08003", 17)).when(connection).commit();

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.ROLLED_BACK, result.getStatementState());
        assertEquals(TransactionOutcome.ROLLED_BACK, result.getTransactionOutcome());
        assertTrue(result.getErrorMessage().contains("commit failed"));
        verify(connection).rollback();
    }

    @Test
    void commitAndRollbackFailureIsUnknown() throws Exception {
        Connection connection = executableConnection();
        doThrow(new SQLException("commit failed")).when(connection).commit();
        doThrow(new SQLException("rollback failed")).when(connection).rollback();

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.UNKNOWN, result.getStatementState());
        assertEquals(TransactionOutcome.UNKNOWN, result.getTransactionOutcome());
    }

    @Test
    void failureBeforeSubmissionIsNotExecuted() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        doThrow(new SQLException("autocommit refused")).when(connection).setAutoCommit(false);

        SqlCommandResult result = EXECUTOR.executeCommand(request(connection, true, null));

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.NOT_EXECUTED, result.getStatementState());
        verify(connection, never()).createStatement();
    }

    @Test
    void timeoutMsMapsToQueryTimeoutSeconds() throws Exception {
        Connection connection = executableConnection();
        Statement statement = connection.createStatement();

        EXECUTOR.executeCommand(request(connection, false, 1500));

        verify(statement).setQueryTimeout(2);
    }

    @Test
    void nullTimeoutLeavesQueryTimeoutUnset() throws Exception {
        Connection connection = executableConnection();
        Statement statement = connection.createStatement();

        EXECUTOR.executeCommand(request(connection, false, null));

        verify(statement, never()).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void defaultTimeoutHookRecognizesJdbcAndVendorSignals() {
        assertTrue(EXECUTOR.isTimeoutException(new SQLTimeoutException("t")));
        assertTrue(EXECUTOR.isTimeoutException(new SQLException("t", "57014")));
        assertTrue(EXECUTOR.isTimeoutException(new SQLException("t", "HY008")));
        assertFalse(EXECUTOR.isTimeoutException(new SQLException("t", "22000", -608)));
        assertFalse(EXECUTOR.isTimeoutException(new SQLException("t", null, 0)));
    }

    private Connection executableConnection() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(false);
        when(statement.getUpdateCount()).thenReturn(-1);
        return connection;
    }

    private Connection failingConnection(SQLException failure) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(failure);
        return connection;
    }

    private SqlCommandRequest request(Connection connection, boolean needTransaction, Integer timeoutMs) {
        SqlCommandRequest request = new SqlCommandRequest();
        request.setConnection(connection);
        request.setOriginalSql("UPDATE T SET C = 1");
        request.setExecuteSql("UPDATE T SET C = 1");
        request.setNeedTransaction(needTransaction);
        request.setTimeoutMs(timeoutMs);
        return request;
    }

    private static final class TestExecutor extends AbstractSqlExecutor {
        @Override
        protected Object getJdbcValue(JdbcValueContext context) {
            return null;
        }
    }
}
