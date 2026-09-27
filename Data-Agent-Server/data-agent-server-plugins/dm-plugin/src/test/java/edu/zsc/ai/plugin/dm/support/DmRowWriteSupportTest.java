package edu.zsc.ai.plugin.dm.support;

import edu.zsc.ai.plugin.dm.value.DmLobPreviewValues;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offline tests for {@link DmRowWriteSupport}: JDBC interactions are mocked,
 * so generated SQL and parameter binding are verified without a DM instance.
 */
class DmRowWriteSupportTest {

    private final DmRowWriteSupport support = new DmRowWriteSupport();

    // ---------- insert ----------

    @Test
    void insertRow_buildsQuotedPreparedInsertAndBindsParams() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        List<TableRowValue> values = List.of(
                new TableRowValue("id", 7L),
                new TableRowValue("name", "alice")
        );

        SqlCommandResult result = support.insertRow(connection, null, "HR", "users", values);

        verify(connection).prepareStatement("INSERT INTO \"HR\".\"users\" (\"id\", \"name\") VALUES (?, ?)");
        verify(statement).setObject(1, 7L);
        verify(statement).setObject(2, "alice");
        assertTrue(result.isSuccess());
        assertFalse(result.isQuery());
        assertEquals(1, result.getAffectedRows());
    }

    @Test
    void insertRow_preservesEmptyStringAsJbdcParameter() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        SqlCommandResult result = support.insertRow(connection, null, "HR", "users",
                List.of(new TableRowValue("name", "")));

        verify(connection).prepareStatement("INSERT INTO \"HR\".\"users\" (\"name\") VALUES (?)");
        verify(statement).setObject(1, "");
        assertTrue(result.isSuccess());
    }

    @Test
    void insertRow_fallsBackToCatalogWhenSchemaBlank() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        support.insertRow(connection, "HR", null, "users", List.of(new TableRowValue("id", 1)));

        verify(connection).prepareStatement("INSERT INTO \"HR\".\"users\" (\"id\") VALUES (?)");
    }

    @Test
    void insertRow_escapesQuotesInIdentifiers() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        support.insertRow(connection, null, "HR", "we\"ird", List.of(new TableRowValue("a\"b", 1)));

        verify(connection).prepareStatement("INSERT INTO \"HR\".\"we\"\"ird\" (\"a\"\"b\") VALUES (?)");
    }

    @Test
    void insertRow_validatesArguments() {
        Connection connection = mock(Connection.class);
        assertThrows(IllegalArgumentException.class,
                () -> support.insertRow(null, null, "HR", "users", List.of(new TableRowValue("id", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> support.insertRow(connection, null, "HR", " ", List.of(new TableRowValue("id", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> support.insertRow(connection, null, "HR", "users", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> support.insertRow(connection, null, "HR", "users", List.of(new TableRowValue(" ", 1))));
    }

    // ---------- delete ----------

    @Test
    void deleteRow_executesPreparedDeleteForSingleMatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement deleteStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM \"HR\".\"users\" WHERE \"id\" = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("DELETE FROM \"HR\".\"users\" WHERE \"id\" = ?"))
                .thenReturn(deleteStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(deleteStatement.executeUpdate()).thenReturn(1);

        SqlCommandResult result = support.deleteRow(connection, null, "HR", "users",
                List.of(new TableRowValue("id", 7L)), false);

        verify(countStatement).setObject(1, 7L);
        verify(deleteStatement).setObject(1, 7L);
        assertTrue(result.isSuccess());
        assertEquals(1, result.getAffectedRows());
        assertEquals("DELETE FROM \"HR\".\"users\" WHERE \"id\" = ?", result.getExecutedSql());
    }

    @Test
    void deleteRow_rejectsAmbiguousMatchWithoutForce() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(countStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(2L);

        SqlCommandResult result = support.deleteRow(connection, null, "HR", "users",
                List.of(new TableRowValue("name", "alice")), false);

        verify(connection).prepareStatement(
                "SELECT COUNT(*) AS total FROM \"HR\".\"users\" WHERE \"name\" = ?");
        verify(countStatement, never()).executeUpdate();
        assertFalse(result.isSuccess());
        assertEquals("Delete target is ambiguous: matched 2 rows. Retry with force=true to continue.",
                result.getErrorMessage());
        assertEquals(2, result.getAffectedRows());
        assertEquals(DmRowWriteSupport.DELETE_REQUIRES_FORCE_CODE, result.getMessages().get(0).getCode());
    }

    @Test
    void deleteRow_failsWhenNoRowsMatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(countStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(0L);

        SqlCommandResult result = support.deleteRow(connection, null, "HR", "users",
                List.of(new TableRowValue("id", 7L)), false);

        verify(countStatement, never()).executeUpdate();
        assertFalse(result.isSuccess());
        assertEquals("No rows matched the selected row", result.getErrorMessage());
    }

    @Test
    void deleteRow_bindsEmptyStringMatchWithoutAssumingCompatibilityMode() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement deleteStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM \"HR\".\"users\" WHERE \"name\" = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("DELETE FROM \"HR\".\"users\" WHERE \"name\" = ?"))
                .thenReturn(deleteStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(deleteStatement.executeUpdate()).thenReturn(1);

        SqlCommandResult result = support.deleteRow(connection, null, "HR", "users",
                List.of(new TableRowValue("name", "")), false);

        assertTrue(result.isSuccess());
        verify(countStatement).setObject(1, "");
        verify(deleteStatement).setObject(1, "");
        assertEquals("DELETE FROM \"HR\".\"users\" WHERE \"name\" = ?", result.getExecutedSql());
    }

    @Test
    void deleteRow_usesIsNullOnlyForExplicitNullMatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement deleteStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM \"HR\".\"users\" WHERE \"name\" IS NULL"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("DELETE FROM \"HR\".\"users\" WHERE \"name\" IS NULL"))
                .thenReturn(deleteStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(deleteStatement.executeUpdate()).thenReturn(1);

        SqlCommandResult result = support.deleteRow(connection, null, "HR", "users",
                java.util.Collections.singletonList(new TableRowValue("name", null)), false);

        assertTrue(result.isSuccess());
        assertEquals("DELETE FROM \"HR\".\"users\" WHERE \"name\" IS NULL", result.getExecutedSql());
    }

    // ---------- update ----------

    @Test
    void updateRow_executesPreparedUpdateForSingleMatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM \"HR\".\"users\" WHERE \"id\" = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("UPDATE \"HR\".\"users\" SET \"name\" = ? WHERE \"id\" = ?"))
                .thenReturn(updateStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(updateStatement.executeUpdate()).thenReturn(1);

        SqlCommandResult result = support.updateRow(connection, null, "HR", "users",
                List.of(new TableRowValue("name", "bob")),
                List.of(new TableRowValue("id", 7L)), false);

        verify(updateStatement).setObject(1, "bob");
        verify(updateStatement).setObject(2, 7L);
        assertTrue(result.isSuccess());
        assertEquals(1, result.getAffectedRows());
    }

    @Test
    void updateRow_rejectsAmbiguousMatchWithoutForce() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(countStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(3L);

        SqlCommandResult result = support.updateRow(connection, null, "HR", "users",
                List.of(new TableRowValue("name", "bob")),
                List.of(new TableRowValue("name", "alice")), false);

        verify(countStatement, never()).executeUpdate();
        assertFalse(result.isSuccess());
        assertEquals("Update target is ambiguous: matched 3 rows. Retry with force=true to continue.",
                result.getErrorMessage());
        assertEquals(3, result.getAffectedRows());
        assertEquals(DmRowWriteSupport.UPDATE_REQUIRES_FORCE_CODE, result.getMessages().get(0).getCode());
    }

    @Test
    void updateRow_preservesEmptyStringSetValue() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM \"HR\".\"users\" WHERE \"id\" = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("UPDATE \"HR\".\"users\" SET \"name\" = ? WHERE \"id\" = ?"))
                .thenReturn(updateStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(updateStatement.executeUpdate()).thenReturn(1);

        support.updateRow(connection, null, "HR", "users",
                List.of(new TableRowValue("name", "")),
                List.of(new TableRowValue("id", 7L)), false);

        verify(updateStatement).setObject(1, "");
        verify(updateStatement).setObject(2, 7L);
    }

    // ---------- LOB preview write-back guard (P0) ----------

    @Test
    void insertRow_rejectsTruncatedLobPreviewWithoutTouchingDatabase() throws Exception {
        Connection connection = mock(Connection.class);

        SqlCommandResult result = support.insertRow(connection, null, "HR", "docs",
                List.of(new TableRowValue("id", 1), new TableRowValue("content", "[CLOB: 1.15MB]")));

        assertFalse(result.isSuccess());
        assertEquals(DmLobPreviewValues.PREVIEW_NOT_WRITABLE_CODE, result.getMessages().get(0).getCode());
        assertTrue(result.getErrorMessage().contains("[CLOB: 1.15MB]"));
        assertTrue(result.getErrorMessage().contains("content"));
        // no statement is ever prepared, let alone executed
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void updateRow_rejectsTruncatedLobPreviewSetValueWithoutTouchingDatabase() throws Exception {
        Connection connection = mock(Connection.class);

        SqlCommandResult result = support.updateRow(connection, null, "HR", "files",
                List.of(new TableRowValue("data", "[BLOB: 1.17MB]")),
                List.of(new TableRowValue("id", 7L)), true);

        assertFalse(result.isSuccess());
        assertEquals(DmLobPreviewValues.PREVIEW_NOT_WRITABLE_CODE, result.getMessages().get(0).getCode());
        assertTrue(result.getErrorMessage().contains("[BLOB: 1.17MB]"));
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void updateRow_allowsOrdinaryBracketedStrings() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM \"HR\".\"users\" WHERE \"id\" = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("UPDATE \"HR\".\"users\" SET \"name\" = ? WHERE \"id\" = ?"))
                .thenReturn(updateStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(updateStatement.executeUpdate()).thenReturn(1);

        // similar-looking but legitimate text values must pass through
        SqlCommandResult result = support.updateRow(connection, null, "HR", "users",
                List.of(new TableRowValue("name", "[BLOB: hello]")),
                List.of(new TableRowValue("id", 7L)), false);

        assertTrue(result.isSuccess());
        verify(updateStatement).setObject(1, "[BLOB: hello]");
    }

    @Test
    void updateRow_validatesArguments() {
        Connection connection = mock(Connection.class);
        List<TableRowValue> setValues = List.of(new TableRowValue("name", "bob"));
        List<TableRowValue> matchValues = List.of(new TableRowValue("id", 7L));
        assertThrows(IllegalArgumentException.class,
                () -> support.updateRow(null, null, "HR", "users", setValues, matchValues, false));
        assertThrows(IllegalArgumentException.class,
                () -> support.updateRow(connection, null, "HR", null, setValues, matchValues, false));
        assertThrows(IllegalArgumentException.class,
                () -> support.updateRow(connection, null, "HR", "users", List.of(), matchValues, false));
        assertThrows(IllegalArgumentException.class,
                () -> support.updateRow(connection, null, "HR", "users", setValues, List.of(), false));
    }
}
