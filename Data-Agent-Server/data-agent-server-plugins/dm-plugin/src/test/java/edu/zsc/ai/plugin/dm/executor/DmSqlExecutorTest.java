package edu.zsc.ai.plugin.dm.executor;

import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmSqlExecutorTest {

    @Test
    void invalidExplainNeverFallsThroughToJdbcStatementExecution() throws Exception {
        Connection connection = mock(Connection.class);
        Statement jdbcStatement = mock(Statement.class);
        SqlCommandRequest request = SqlCommandRequest.ofWithoutTransaction(connection,
                "EXPLAIN SELECT FROM T", "EXPLAIN SELECT FROM T", null, null);

        SqlCommandResult result = new DmSqlExecutor().executeCommand(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Invalid DM SQL"));
        assertEquals(StatementExecutionState.NOT_EXECUTED, result.getStatementState());
        assertEquals(TransactionOutcome.NONE, result.getTransactionOutcome());
        verify(connection, never()).createStatement();
        verify(jdbcStatement, never()).execute("EXPLAIN SELECT FROM T");
    }

    @Test
    void dmTimeoutErrorCodeIsRecognized() {
        DmSqlExecutor executor = new DmSqlExecutor();
        assertTrue(executor.isTimeoutException(new SQLException("请求执行超时", "22000", -608)));
        assertFalse(executor.isTimeoutException(new SQLException("无效的模式名", "3F000", -2103)));
        // standard signals still honored via the base hook
        assertTrue(executor.isTimeoutException(new SQLTimeoutException("t", "57014")));
    }

    @Test
    void dmTimeoutDuringExecutionYieldsTimedOutState() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("请求执行超时", "22000", -608));

        SqlCommandRequest request = SqlCommandRequest.ofWithoutTransaction(connection,
                "SELECT 1 FROM DUAL", "SELECT 1 FROM DUAL", null, null);
        SqlCommandResult result = new DmSqlExecutor().executeCommand(request);

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.TIMED_OUT, result.getStatementState());
        assertEquals(TransactionOutcome.NONE, result.getTransactionOutcome());
    }

    @Test
    void dmCancellationErrorCodeIsRecognized() {
        DmSqlExecutor executor = new DmSqlExecutor();
        // live-verified: Statement.cancel() surfaces -6515 操作被取消 on the executing thread
        assertTrue(executor.isCancellationException(new SQLException("操作被取消", "25000", -6515)));
        // timeout is NOT a cancellation
        assertFalse(executor.isCancellationException(new SQLException("请求执行超时", "22000", -608)));
        // generic errors are never claimed as cancellations
        assertFalse(executor.isCancellationException(new SQLException("无效的表名", "42000", -2103)));
    }

    @Test
    void dmCancelDuringExecutionYieldsCancelledState() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("操作被取消", "25000", -6515));

        SqlCommandRequest request = SqlCommandRequest.ofWithoutTransaction(connection,
                "SELECT COUNT(*) FROM SYSOBJECTS a, SYSOBJECTS b", "SELECT COUNT(*) FROM SYSOBJECTS a, SYSOBJECTS b",
                null, null);
        SqlCommandResult result = new DmSqlExecutor().executeCommand(request);

        assertFalse(result.isSuccess());
        assertEquals(StatementExecutionState.CANCELLED, result.getStatementState());
        assertEquals(-6515, result.getErrorCode());
        assertEquals("25000", result.getSqlState());
        assertEquals(TransactionOutcome.NONE, result.getTransactionOutcome());
    }

    @Test
    void dmNetworkLossDuringExecutionIsUnknownNotFailed() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("网络通信异常", "08S01", 6001));

        SqlCommandRequest request = SqlCommandRequest.ofWithoutTransaction(connection,
                "SELECT 1 FROM DUAL", "SELECT 1 FROM DUAL", null, null);
        SqlCommandResult result = new DmSqlExecutor().executeCommand(request);

        assertFalse(result.isSuccess());
        // connection lost: outcome undecidable — must not be claimed FAILED or CANCELLED
        assertEquals(StatementExecutionState.UNKNOWN, result.getStatementState());
        assertEquals(TransactionOutcome.NONE, result.getTransactionOutcome());
    }
}
