package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmConstraintManagerTest {

    private final DmObjectQuerySupport support = mock(DmObjectQuerySupport.class);
    private final Connection connection = mock(Connection.class);
    private final DmConstraintManager manager = new DmConstraintManager(support);

    @Test
    void preservesCompositeForeignKeyColumnOrder() {
        when(support.resolveSchema(connection, "Mixed")).thenReturn("Mixed");
        when(support.query(connection, DmObjectSql.SQL_LIST_CONSTRAINTS, "Mixed", "Child"))
                .thenReturn(List.of(row("b", "y"), row("a", "x")));

        ConstraintMetadata fk = manager.getConstraints(connection, null, "Mixed", "Child").get(0);
        assertEquals("FK1", fk.name());
        assertEquals(List.of("b", "a"), fk.columns());
        assertEquals(List.of("y", "x"), fk.referencedColumns());
        assertEquals("Parent", fk.referencedTable());
        assertTrue(DmObjectSql.SQL_LIST_CONSTRAINTS.contains("rc.POSITION = cc.POSITION"));
    }

    @Test
    void dropQuotesEachObjectName() {
        when(support.resolveSchema(connection, "Mixed")).thenReturn("Mixed");
        manager.deleteConstraint(connection, null, "Mixed", "Child", "fk lower");
        verify(support).executeDdl(connection,
                "ALTER TABLE \"Mixed\".\"Child\" DROP CONSTRAINT \"fk lower\"",
                "Failed to delete constraint");
    }

    private static Map<String, Object> row(String column, String referencedColumn) {
        Map<String, Object> row = new HashMap<>();
        row.put("CONSTRAINT_NAME", "FK1");
        row.put("CONSTRAINT_TYPE", "R");
        row.put("TABLE_NAME", "Child");
        row.put("STATUS", "ENABLED");
        row.put("R_OWNER", "Mixed");
        row.put("REF_TABLE_NAME", "Parent");
        row.put("COLUMN_NAME", column);
        row.put("REF_COLUMN_NAME", referencedColumn);
        return row;
    }
}
