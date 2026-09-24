package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live smoke test against a real DM instance. Run with:
 * mvn test -pl data-agent-server-plugins/dm-plugin -Ddm.live=true -Dtest=DmLiveSmokeTest
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmLiveSmokeTest {

    private static final String DRIVER_JAR = System.getProperty("dm.driver.jar",
            System.getProperty("user.home") + "/.data-agent/drivers/dm/DmJdbcDriver18-8.1.3.140.jar");

    @Test
    void connectAndBrowseMetadata() throws Exception {
        Dm8Plugin plugin = new Dm8Plugin();
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(System.getProperty("dm.host", "localhost"));
        config.setPort(Integer.getInteger("dm.port", 25236));
        config.setUsername(System.getProperty("dm.user", "SYSDBA"));
        config.setPassword(System.getProperty("dm.password", "Chat2DB_dm2762"));
        config.setDriverJarPath(DRIVER_JAR);

        try (Connection conn = plugin.connect(config)) {
            // catalog placeholder must be ignored (explorer passes a pseudo catalog)
            List<String> schemas = plugin.getSchemas(conn, "localhost@25236");
            System.out.println("schemas = " + schemas);
            assertFalse(schemas.isEmpty());
            assertTrue(schemas.contains("SYSDBA"));

            String schema = schemas.stream().filter(s -> s.equalsIgnoreCase("SYSDBA")).findFirst().orElse(schemas.get(0));
            List<String> tables = plugin.getTableNames(conn, "localhost@25236", schema);
            System.out.println("tables in " + schema + " = " + tables);
            assertFalse(tables.isEmpty());

            String table = tables.get(0);
            List<ColumnMetadata> columns = plugin.getColumns(conn, "localhost@25236", schema, table);
            System.out.println("columns of " + table + " = " + columns.size());
            assertFalse(columns.isEmpty());

            SqlCommandResult data = plugin.getTableData(conn, "localhost@25236", schema, table, 0, 5);
            System.out.println("rows fetched: cols=" + (data.getColumns() == null ? 0 : data.getColumns().size()));

            String ddl = plugin.getTableDdl(conn, "localhost@25236", schema, table);
            System.out.println("ddl length = " + (ddl == null ? 0 : ddl.length()));
        }
    }

    @Test
    void listTriggersUsesDmDictionaryColumns() throws Exception {
        Dm8Plugin plugin = new Dm8Plugin();
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(System.getProperty("dm.host", "localhost"));
        config.setPort(Integer.getInteger("dm.port", 25236));
        config.setUsername(System.getProperty("dm.user", "SYSDBA"));
        config.setPassword(System.getProperty("dm.password", "Chat2DB_dm2762"));
        config.setDriverJarPath(DRIVER_JAR);

        try (Connection conn = plugin.connect(config)) {
            // must not raise "invalid column TRIGGER_TYPE" against the real DM dictionary
            List<?> triggers = plugin.getTriggers(conn, null, "CTISYS", null);
            System.out.println("triggers in CTISYS = " + triggers.size());
            List<?> triggersSysdba = plugin.getTriggers(conn, null, "SYSDBA", null);
            System.out.println("triggers in SYSDBA = " + triggersSysdba.size());
        }
    }

    @Test
    void listViewsFunctionsProceduresIndexes() throws Exception {
        Dm8Plugin plugin = new Dm8Plugin();
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(System.getProperty("dm.host", "localhost"));
        config.setPort(Integer.getInteger("dm.port", 25236));
        config.setUsername(System.getProperty("dm.user", "SYSDBA"));
        config.setPassword(System.getProperty("dm.password", "Chat2DB_dm2762"));
        config.setDriverJarPath(DRIVER_JAR);

        try (Connection conn = plugin.connect(config)) {
            System.out.println("views SYSDBA = " + plugin.getViews(conn, null, "SYSDBA").size());
            System.out.println("functions SYSDBA = " + plugin.getFunctions(conn, null, "SYSDBA").size());
            System.out.println("procedures SYSDBA = " + plugin.getProcedures(conn, null, "SYSDBA").size());
            System.out.println("sequences SYSDBA = " + plugin.getSequences(conn, null, "SYSDBA").size());
            System.out.println("indexes CHAT2DB_EXPLAIN_TEST = "
                    + plugin.getIndexes(conn, null, "SYSDBA", "CHAT2DB_EXPLAIN_TEST").size());
            System.out.println("constraints CHAT2DB_EXPLAIN_TEST = "
                    + plugin.getConstraints(conn, null, "SYSDBA", "CHAT2DB_EXPLAIN_TEST").size());
            System.out.println("triggers of table = " + plugin.getTriggers(conn, null, "SYSDBA", "CHAT2DB_EXPLAIN_TEST").size());
        }
    }

    @Test
    void connectWithSchemaSetsCurrentSchema() throws Exception {
        Dm8Plugin plugin = new Dm8Plugin();
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(System.getProperty("dm.host", "localhost"));
        config.setPort(Integer.getInteger("dm.port", 25236));
        config.setUsername(System.getProperty("dm.user", "SYSDBA"));
        config.setPassword(System.getProperty("dm.password", "Chat2DB_dm2762"));
        config.setDriverJarPath(DRIVER_JAR);
        config.setSchema("TEST");

        try (Connection conn = plugin.connect(config)) {
            String currentSchema = conn.getSchema();
            System.out.println("current schema = " + currentSchema);
            assertTrue("TEST".equalsIgnoreCase(currentSchema), "expected current schema TEST but got " + currentSchema);
        }
    }
}
