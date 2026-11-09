package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.dm.constant.DmSqlTemplate;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmTableManagerTest {

    @Test
    void tableDdlUsesExactMetadataNameForQuotedObjectsAndComments() {
        Connection connection = mock(Connection.class);
        DmMetadataSupport metadata = mock(DmMetadataSupport.class);
        when(metadata.resolveSchema(connection, "CaseSchema")).thenReturn("CaseSchema");
        when(metadata.getObjectDdl(connection, "CaseSchema", "CaseTable",
                DmSqlTemplate.SQL_GET_TABLE_DDL, "table"))
                .thenReturn("CREATE TABLE \"CaseSchema\".\"CaseTable\" (\"CaseColumn\" INT)");
        when(metadata.getTableComment(connection, "CaseSchema", "CaseTable"))
                .thenReturn("table comment");
        when(metadata.getColumnComments(connection, "CaseSchema", "CaseTable"))
                .thenReturn(List.<String[]>of(new String[]{"CaseColumn", "column comment"}));

        String ddl = new DmTableManager(metadata)
                .getTableDdl(connection, null, "CaseSchema", "CaseTable");

        verify(metadata).getTableComment(connection, "CaseSchema", "CaseTable");
        verify(metadata).getColumnComments(connection, "CaseSchema", "CaseTable");
        assertTrue(ddl.contains("COMMENT ON TABLE \"CaseSchema\".\"CaseTable\""));
        assertTrue(ddl.contains("COMMENT ON COLUMN \"CaseSchema\".\"CaseTable\".\"CaseColumn\""));
    }

    @Test
    void tableCommentsUseResolvedSchema() {
        Connection connection = mock(Connection.class);
        DmMetadataSupport metadata = mock(DmMetadataSupport.class);
        when(metadata.resolveSchema(connection, null)).thenReturn("HR");
        when(metadata.getTableComments(connection, "HR"))
                .thenReturn(Map.of("USERS", "User accounts"));

        DmTableManager manager = new DmTableManager(metadata);

        assertEquals(Map.of("USERS", "User accounts"),
                manager.getTableComments(connection, null, null));

        manager.updateTableComment(connection, null, null, "USERS", "Owner's note");

        verify(metadata).updateTableComment(connection, "HR", "USERS", "Owner's note");
    }
}
