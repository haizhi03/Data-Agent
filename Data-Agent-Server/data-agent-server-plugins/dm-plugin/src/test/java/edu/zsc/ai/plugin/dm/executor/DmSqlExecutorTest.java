package edu.zsc.ai.plugin.dm.executor;

import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        verify(connection, never()).createStatement();
        verify(jdbcStatement, never()).execute("EXPLAIN SELECT FROM T");
    }
}
