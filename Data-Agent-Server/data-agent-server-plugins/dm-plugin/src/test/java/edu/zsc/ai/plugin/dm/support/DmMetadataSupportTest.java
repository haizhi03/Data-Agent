package edu.zsc.ai.plugin.dm.support;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmMetadataSupportTest {

    @Test
    void preservesCaseSensitiveSchemaName() {
        assertEquals("CaseSensitive", new DmMetadataSupport().resolveSchema(mock(Connection.class), "CaseSensitive"));
    }

    @Test
    void preservesCaseSensitiveObjectNameForDdlLookup() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("ddl sql")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("CREATE TABLE \"MixedCase\" (ID INT)");

        new DmMetadataSupport().getObjectDdl(connection, "CaseSensitive", "MixedCase", "ddl sql", "table");

        verify(statement).setString(1, "MixedCase");
        verify(statement).setString(2, "CaseSensitive");
    }

    @Test
    void getTableCommentsReadsNonBlankCommentsForOwner() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(edu.zsc.ai.plugin.dm.constant.DmSqlTemplate.SQL_TABLE_COMMENTS))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("USERS", "BLANK");
        when(resultSet.getString(2)).thenReturn("User accounts", " ");

        Map<String, String> comments = new DmMetadataSupport().getTableComments(connection, "HR");

        verify(statement).setString(1, "HR");
        assertEquals(Map.of("USERS", "User accounts"), comments);
    }

    @Test
    void buildTableCommentSqlQuotesIdentifiersAndDoublesQuotes() {
        DmMetadataSupport support = new DmMetadataSupport();

        assertEquals("COMMENT ON TABLE \"HR\".\"users\" IS 'Owner''s note'",
                support.buildTableCommentSql("HR", "users", "Owner's note"));
        assertEquals("COMMENT ON TABLE \"HR\".\"users\" IS ''",
                support.buildTableCommentSql("HR", "users", ""));
    }
}
