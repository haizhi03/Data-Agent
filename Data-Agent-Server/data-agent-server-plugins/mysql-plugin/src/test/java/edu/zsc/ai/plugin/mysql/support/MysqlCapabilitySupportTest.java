package edu.zsc.ai.plugin.mysql.support;

import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.mysql.constant.MySqlTemplate;
import edu.zsc.ai.plugin.mysql.executor.MySQLSqlExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlCapabilitySupportTest {

    @Test
    void getTableComments_readsNonBlankCommentsForSchema() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(MySqlTemplate.SQL_LIST_TABLE_COMMENTS)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("users", "blank");
        when(resultSet.getString(2)).thenReturn("User accounts", " ");

        Map<String, String> comments = new MysqlCapabilitySupport(mock(MySQLSqlExecutor.class))
                .getTableComments(connection, "demo");

        verify(statement).setString(1, "demo");
        assertEquals(Map.of("users", "User accounts"), comments);
    }

    @Test
    void updateTableComment_qualifiesNameAndEscapesLiteral() {
        MySQLSqlExecutor executor = mock(MySQLSqlExecutor.class);
        SqlCommandResult success = new SqlCommandResult();
        success.setSuccess(true);
        when(executor.executeCommand(any(SqlCommandRequest.class))).thenReturn(success);

        new MysqlCapabilitySupport(executor).updateTableComment(
                mock(Connection.class), "demo", "users", "Owner's \\ note");

        ArgumentCaptor<SqlCommandRequest> captor = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(executor).executeCommand(captor.capture());
        assertEquals("ALTER TABLE demo.users COMMENT = 'Owner''s \\\\ note'",
                captor.getValue().getExecuteSql());
    }
}
