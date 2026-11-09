package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.constant.JdbcMetaDataConstants;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmColumnManagerTest {

    @Test
    void supplementsIdentityAndMissingCommentsTogether() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        ResultSet columns = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getPrimaryKeys(null, "HR", "USERS")).thenReturn(primaryKeys);
        when(databaseMetaData.getColumns(null, "HR", "USERS", null)).thenReturn(columns);
        when(columns.next()).thenReturn(true, true, false);
        when(columns.getString(JdbcMetaDataConstants.COLUMN_NAME)).thenReturn("ID", "NAME");
        when(columns.getString(JdbcMetaDataConstants.TYPE_NAME)).thenReturn("BIGINT", "VARCHAR");
        when(columns.getInt(JdbcMetaDataConstants.NULLABLE)).thenReturn(ResultSetMetaData.columnNullable);
        when(columns.getInt(JdbcMetaDataConstants.ORDINAL_POSITION)).thenReturn(1, 2);
        when(columns.getString(JdbcMetaDataConstants.REMARKS)).thenReturn("identifier", "");

        DmMetadataSupport support = mock(DmMetadataSupport.class);
        when(support.resolveSchema(connection, "HR")).thenReturn("HR");
        when(support.getColumnComments(connection, "HR", "USERS"))
                .thenReturn(List.<String[]>of(new String[]{"NAME", "display name"}));
        when(support.getAutoIncrementColumns(connection, "HR", "USERS")).thenReturn(Set.of("ID"));

        List<ColumnMetadata> result = new DmColumnManager(support)
                .getColumns(connection, null, "HR", "USERS");

        assertTrue(result.get(0).isAutoIncrement());
        assertFalse(result.get(1).isAutoIncrement());
        assertEquals("identifier", result.get(0).remarks());
        assertEquals("display name", result.get(1).remarks());
    }

    @Test
    void identityLookupStillRunsWhenEveryCommentIsPresent() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        ResultSet columns = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getPrimaryKeys(null, "HR", "USERS")).thenReturn(primaryKeys);
        when(databaseMetaData.getColumns(null, "HR", "USERS", null)).thenReturn(columns);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString(JdbcMetaDataConstants.COLUMN_NAME)).thenReturn("ID");
        when(columns.getString(JdbcMetaDataConstants.TYPE_NAME)).thenReturn("BIGINT");
        when(columns.getInt(JdbcMetaDataConstants.ORDINAL_POSITION)).thenReturn(1);
        when(columns.getString(JdbcMetaDataConstants.REMARKS)).thenReturn("identifier");

        DmMetadataSupport support = mock(DmMetadataSupport.class);
        when(support.resolveSchema(connection, "HR")).thenReturn("HR");
        when(support.getAutoIncrementColumns(connection, "HR", "USERS")).thenReturn(Set.of("ID"));

        List<ColumnMetadata> result = new DmColumnManager(support)
                .getColumns(connection, null, "HR", "USERS");

        assertTrue(result.get(0).isAutoIncrement());
        verify(support, never()).getColumnComments(connection, "HR", "USERS");
    }
}
