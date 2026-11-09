package edu.zsc.ai.plugin.mysql.manager;

import edu.zsc.ai.plugin.mysql.support.MysqlCapabilitySupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlTableManagerTest {

    @Test
    void tableCommentsUseResolvedDatabase() {
        Connection connection = mock(Connection.class);
        MysqlCapabilitySupport support = mock(MysqlCapabilitySupport.class);
        when(support.resolveDatabase(null, "demo")).thenReturn("demo");
        when(support.getTableComments(connection, "demo"))
                .thenReturn(Map.of("users", "User accounts"));

        MysqlTableManager manager = new MysqlTableManager(support);

        assertEquals(Map.of("users", "User accounts"),
                manager.getTableComments(connection, null, "demo"));

        manager.updateTableComment(connection, null, "demo", "users", "Owner's note");

        verify(support).updateTableComment(connection, "demo", "users", "Owner's note");
    }
}
