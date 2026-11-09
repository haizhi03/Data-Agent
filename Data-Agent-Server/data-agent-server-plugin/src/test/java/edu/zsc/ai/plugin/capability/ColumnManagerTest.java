package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.constant.JdbcMetaDataConstants;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ColumnManagerTest {

    @Test
    void readsStandardJdbcAutoIncrementMetadata() throws Exception {
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
        when(columns.getString(JdbcMetaDataConstants.IS_AUTOINCREMENT)).thenReturn("YES", "NO");

        List<ColumnMetadata> result = new ColumnManager() { }
                .getColumns(connection, null, "HR", "USERS");

        assertTrue(result.get(0).isAutoIncrement());
        assertFalse(result.get(1).isAutoIncrement());
    }
}
