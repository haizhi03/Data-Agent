package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-00 environment certification probe against the real DM8 instance.
 * Prints ENV.KEY=VALUE lines that are transcribed into
 * docs/certification/dm8/M1-environment.md. Also creates the dedicated
 * certification schema M1_CERT (idempotent).
 * Run with: mvn test -pl data-agent-server-plugins/dm-plugin -Ddm.live=true -Ddm.password=... -Dtest=DmEnvironmentCertificationTest
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmEnvironmentCertificationTest {

    private static final String CERT_SCHEMA = "M1_CERT";

    @Test
    void certifyEnvironment() throws Exception {
        Dm8Plugin plugin = new Dm8Plugin();
        ConnectionConfig config = DmLiveTestFixture.baseConfig();

        System.out.println("ENV.JAVA_VERSION=" + System.getProperty("java.version"));
        System.out.println("ENV.JAVA_VENDOR=" + System.getProperty("java.vendor"));
        System.out.println("ENV.OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        System.out.println("ENV.HOST=" + config.getHost());
        System.out.println("ENV.PORT=" + config.getPort());
        System.out.println("ENV.USERNAME=" + config.getUsername());

        try (Connection conn = plugin.connect(config)) {
            DatabaseMetaData meta = conn.getMetaData();
            System.out.println("ENV.DB_PRODUCT=" + meta.getDatabaseProductName());
            System.out.println("ENV.DB_VERSION=" + meta.getDatabaseProductVersion());
            System.out.println("ENV.DRIVER_NAME=" + meta.getDriverName());
            System.out.println("ENV.DRIVER_VERSION=" + meta.getDriverVersion());
            System.out.println("ENV.JDBC_SPEC=" + meta.getJDBCMajorVersion() + "." + meta.getJDBCMinorVersion());

            dumpQuery(conn, "V$VERSION", "SELECT * FROM V$VERSION");
            dumpQuery(conn, "V$INSTANCE", "SELECT * FROM V$INSTANCE");
            dumpQuery(conn, "CURRENT_USER", "SELECT USER AS CURRENT_USER_VALUE FROM DUAL");
            dumpQuery(conn, "INI",
                    "SELECT PARA_NAME, PARA_VALUE FROM V$DM_INI WHERE PARA_NAME IN ("
                            + "'CASE_SENSITIVE','COMPATIBLE_MODE','UNICODE_FLAG','CHARSET',"
                            + "'LENGTH_IN_CHAR','BLANK_PAD_MODE','PK_WITH_CLUSTER','DB_GLOBAL_UNIQUE')");
            dumpQuery(conn, "SESSION_PRIVS_COUNT", "SELECT COUNT(*) AS PRIV_COUNT FROM SESSION_PRIVS");
            dumpQuery(conn, "SESSION_ROLES", "SELECT * FROM SESSION_ROLES");
            dumpQuery(conn, "UNICODE_FLAG_FUNC", "SELECT SF_GET_UNICODE_FLAG() AS UNICODE_FLAG_FUNC FROM DUAL");
            dumpQuery(conn, "CASE_SENSITIVE_FUNC", "SELECT SF_GET_CASE_SENSITIVE_FLAG() AS CASE_SENSITIVE_FUNC FROM DUAL");
            dumpQuery(conn, "INI_LIKE_CASE",
                    "SELECT PARA_NAME, PARA_VALUE FROM V$DM_INI WHERE PARA_NAME LIKE '%CASE%' OR PARA_NAME LIKE '%CHAR%'");

            // dedicated certification schema, idempotent
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + CERT_SCHEMA + " AUTHORIZATION " + config.getUsername());
                System.out.println("ENV.CERT_SCHEMA_CREATED=" + CERT_SCHEMA);
            } catch (SQLException e) {
                // fallback for engines without IF NOT EXISTS on CREATE SCHEMA
                if (schemaExists(conn)) {
                    System.out.println("ENV.CERT_SCHEMA_CREATED=" + CERT_SCHEMA + " (already existed)");
                } else {
                    try (Statement st = conn.createStatement()) {
                        st.execute("CREATE SCHEMA " + CERT_SCHEMA + " AUTHORIZATION " + config.getUsername());
                        System.out.println("ENV.CERT_SCHEMA_CREATED=" + CERT_SCHEMA);
                    }
                }
            }
            assertTrue(schemaExists(conn), "certification schema M1_CERT must exist");
            System.out.println("ENV.CERT_SCHEMA=" + CERT_SCHEMA);
        }
    }

    private boolean schemaExists(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM ALL_USERS WHERE USERNAME = '" + CERT_SCHEMA + "'")) {
            if (rs.next() && rs.getLong(1) > 0) {
                return true;
            }
        }
        try (ResultSet rs = conn.getMetaData().getSchemas()) {
            while (rs.next()) {
                if (CERT_SCHEMA.equalsIgnoreCase(rs.getString(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void dumpQuery(Connection conn, String label, String sql) {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData rsmd = rs.getMetaData();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                    if (i > 1) {
                        row.append(" | ");
                    }
                    row.append(rsmd.getColumnLabel(i)).append('=').append(rs.getString(i));
                }
                System.out.println("ENV." + label + ": " + row);
            }
        } catch (SQLException e) {
            System.out.println("ENV." + label + ": <query failed> sqlState=" + e.getSQLState()
                    + " errorCode=" + e.getErrorCode() + " message=" + e.getMessage());
        }
    }
}
