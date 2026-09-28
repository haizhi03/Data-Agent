package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestFixture;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestRun;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live capability audit against a real DM instance (TEST schema).
 * Each run creates uniquely named M1T_<timestamp>_<random>_* objects via
 * DmLiveTestRun and drops only those on completion.
 * Run with: mvn test -pl data-agent-server-plugins/dm-plugin -Ddm.live=true -Ddm.password=... -Dtest=DmLiveCapabilityAuditTest
 * Credentials come only from system properties / environment; tests skip when absent.
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmLiveCapabilityAuditTest {

    private static final String SCHEMA = "TEST";

    private ConnectionConfig config() {
        return DmLiveTestFixture.baseConfig();
    }

    @Test
    void auditWriteAndDdlCapabilities() throws Exception {
        Dm8Plugin plugin = new Dm8Plugin();
        try (Connection conn = plugin.connect(config());
             DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA)) {
            run.sweepStaleRunObjects();
            String table = run.name("T");
            String tableRenamed = run.name("T2");
            String view = run.name("V");
            String proc = run.name("P");
            String func = run.name("F");
            String trigger = run.name("TRG");

            // setup
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + table
                    + " (ID INT PRIMARY KEY, NAME VARCHAR(50), AMOUNT DECIMAL(10,2), CREATED DATETIME)");
            run.track("TABLE", table);

            // 1. row write
            plugin.insertRow(conn, null, SCHEMA, table, List.of(
                    new TableRowValue("ID", 1), new TableRowValue("NAME", "alpha"), new TableRowValue("AMOUNT", 12.5)));
            plugin.insertRow(conn, null, SCHEMA, table, List.of(
                    new TableRowValue("ID", 2), new TableRowValue("NAME", "beta")));
            long count = plugin.getTableDataCount(conn, null, SCHEMA, table);
            System.out.println("[audit] insertRow ok, count=" + count);
            assertEquals(2, count);

            plugin.updateRow(conn, null, SCHEMA, table,
                    List.of(new TableRowValue("NAME", "alpha2")),
                    List.of(new TableRowValue("ID", 1)), false);
            plugin.deleteRow(conn, null, SCHEMA, table,
                    List.of(new TableRowValue("ID", 2)), false);
            System.out.println("[audit] updateRow/deleteRow ok, count=" + plugin.getTableDataCount(conn, null, SCHEMA, table));

            // 2. filtered query
            long filtered = plugin.getTableDataCount(conn, null, SCHEMA, table, "ID = 1");
            System.out.println("[audit] filtered count=" + filtered);
            assertEquals(1, filtered);

            // 3. DDL of each object type
            run.execQuietly("CREATE VIEW " + SCHEMA + "." + view + " AS SELECT ID, NAME FROM " + SCHEMA + "." + table);
            run.track("VIEW", view);
            run.execQuietly("CREATE OR REPLACE PROCEDURE " + SCHEMA + "." + proc + " AS BEGIN NULL; END;");
            run.track("PROCEDURE", proc);
            run.execQuietly("CREATE OR REPLACE FUNCTION " + SCHEMA + "." + func + " RETURN INT AS BEGIN RETURN 1; END;");
            run.track("FUNCTION", func);
            run.execQuietly("CREATE TRIGGER " + SCHEMA + "." + trigger + " BEFORE INSERT ON " + SCHEMA + "." + table + " BEGIN NULL; END;");
            run.track("TRIGGER", trigger);

            String tableDdl = plugin.getTableDdl(conn, null, SCHEMA, table);
            System.out.println("[audit] table DDL:\n" + tableDdl);
            assertNotNull(tableDdl);

            String viewDdl = plugin.getViewDdl(conn, null, SCHEMA, view);
            System.out.println("[audit] view DDL=" + (viewDdl == null ? "NULL" : viewDdl.substring(0, Math.min(80, viewDdl.length()))));

            String procDdl = plugin.getProcedureDdl(conn, null, SCHEMA, proc);
            System.out.println("[audit] proc DDL=" + (procDdl == null ? "NULL" : procDdl.substring(0, Math.min(80, procDdl.length()))));

            String funcDdl = plugin.getFunctionDdl(conn, null, SCHEMA, func);
            System.out.println("[audit] func DDL=" + (funcDdl == null ? "NULL" : funcDdl.substring(0, Math.min(80, funcDdl.length()))));

            String trgDdl = plugin.getTriggerDdl(conn, null, SCHEMA, trigger);
            System.out.println("[audit] trigger DDL=" + (trgDdl == null ? "NULL" : trgDdl.substring(0, Math.min(80, trgDdl.length()))));

            // 4. trigger list now non-empty
            System.out.println("[audit] triggers=" + plugin.getTriggers(conn, null, SCHEMA, null));
            assertEquals(1, plugin.getTriggers(conn, null, SCHEMA, null).size());

            // 5. rename
            plugin.renameTable(conn, null, SCHEMA, table, tableRenamed);
            run.track("TABLE", tableRenamed);
            assertTrue(plugin.getTableNames(conn, null, SCHEMA).contains(tableRenamed));
            plugin.renameTable(conn, null, SCHEMA, tableRenamed, table);
            System.out.println("[audit] renameTable ok");

            // 6. searchTables / countTables
            System.out.println("[audit] searchTables " + run.prefix() + "% ="
                    + plugin.searchTables(conn, null, SCHEMA, run.prefix())
                    + " count=" + plugin.countTables(conn, null, SCHEMA, run.prefix()));

            // 7. sql splitter on PL/SQL-style block
            List<String> stmts = plugin.split("CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;\n/\nSELECT 1;\nSELECT 2;");
            System.out.println("[audit] split result=" + stmts);

            // 8. view data
            var viewData = plugin.getViewData(conn, null, SCHEMA, view, 0, 10);
            System.out.println("[audit] view data rows ok, cols=" + (viewData.getColumns() == null ? 0 : viewData.getColumns().size()));
        }
    }
}
