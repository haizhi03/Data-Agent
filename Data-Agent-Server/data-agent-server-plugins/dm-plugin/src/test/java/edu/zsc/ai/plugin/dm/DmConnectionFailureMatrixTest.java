package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestFixture;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestRun;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-02 live connection-failure injection matrix against the real DM8 instance.
 * For each scenario the actual exception type / sqlState / errorCode / elapsed
 * time is captured and printed as a MATRIX| line; these measured values are
 * transcribed into docs/certification/dm8/M1-connection-failure-matrix.md as
 * the mapping basis for Agent D's ConnectionFailureException classification.
 *
 * <p>Also verifies that no physical session leaks after each failure
 * (V$SESSIONS count for the test user returns to baseline; current code has
 * closeAfterInitializationFailure in DmConnectionManager).
 *
 * Run with: mvn test -pl data-agent-server-plugins/dm-plugin -Ddm.live=true -Ddm.password=... -Dtest=DmConnectionFailureMatrixTest
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DmConnectionFailureMatrixTest {

    private static final long TIMEOUT_WATCHDOG_MS = 60_000;

    private record ProbeResult(Throwable failure, long elapsedMs) {
    }

    private final Dm8Plugin plugin = new Dm8Plugin();

    @Test
    @Order(0)
    void sanityConnectSucceeds() throws Exception {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        try (Connection conn = plugin.connect(config)) {
            assertTrue(conn.isValid(2));
            System.out.println("MATRIX|sanityConnect|OK|-|-|-|"
                    + "|baseline session count=" + countMySessions(conn));
        }
    }

    @Test
    @Order(1)
    void driverMissing() {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        config.setDriverJarPath(tempDir().resolve("no-such-driver.jar").toString());
        ProbeResult result = probe(config);
        report("driverMissing", result);
        assertNotNull(result.failure());
    }

    @Test
    @Order(2)
    void driverCorrupted() throws IOException {
        Path fakeJar = tempDir().resolve(DmLiveTestRun.PREFIX_NAMESPACE + "corrupted.jar");
        Files.writeString(fakeJar, "this is not a jar file at all");
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        config.setDriverJarPath(fakeJar.toString());
        ProbeResult result = probe(config);
        report("driverCorrupted", result);
        assertNotNull(result.failure());
    }

    @Test
    @Order(3)
    void driverVersionMismatch() throws IOException {
        // (a) valid but empty jar: driver class absent, simulates a wrong driver artifact
        Path emptyJar = tempDir().resolve(DmLiveTestRun.PREFIX_NAMESPACE + "empty.jar");
        try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(emptyJar))) {
            // empty but valid zip
        }
        ConnectionConfig configA = DmLiveTestFixture.baseConfig();
        configA.setDriverJarPath(emptyJar.toString());
        report("driverVersionMismatch[emptyJarNoDriverClass]", probe(configA));

        // (b) jar whose dm.jdbc.driver.DmDriver does not implement java.sql.Driver:
        // class present but incompatible API, simulates a genuine version mismatch
        Path wrongApiJar = buildJarWithFakeDmDriver();
        ConnectionConfig configB = DmLiveTestFixture.baseConfig();
        configB.setDriverJarPath(wrongApiJar.toString());
        ProbeResult resultB = probe(configB);
        report("driverVersionMismatch[incompatibleDriverClass]", resultB);
        assertNotNull(resultB.failure());
    }

    @Test
    @Order(4)
    void wrongPassword() {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        config.setPassword("M1T_wrong_password_for_failure_matrix");
        ProbeResult result = probe(config);
        report("wrongPassword", result);
        assertNotNull(result.failure());
    }

    @Test
    @Order(5)
    void networkTimeout() {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        config.setHost("10.255.255.1"); // non-routable; connect must time out, not hang
        config.setPort(5236);
        config.setTimeout(2); // -> connectTimeout=2000ms via DmJdbcConnectionBuilder
        ProbeResult result = probe(config);
        report("networkTimeout", result);
        assertNotNull(result.failure());
    }

    @Test
    @Order(6)
    void invalidSchema() {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        config.setSchema("M1T_NO_SUCH_SCHEMA_FOR_MATRIX");
        ProbeResult result = probe(config);
        report("invalidSchema", result);
        assertNotNull(result.failure());
    }

    @Test
    @Order(7)
    void serverUnavailable() {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        config.setPort(25999); // nothing listens here
        ProbeResult result = probe(config);
        report("serverUnavailable", result);
        assertNotNull(result.failure());
    }

    @Test
    @Order(8)
    void noPhysicalConnectionLeakAfterFailures() throws Exception {
        // run the SQL-level failures once more, then confirm the session count
        // for our user returns to baseline (closeAfterInitializationFailure works)
        ConnectionConfig baseline = DmLiveTestFixture.baseConfig();
        try (Connection conn = plugin.connect(baseline)) {
            int before = countMySessions(conn);

            ConnectionConfig wrongPwd = DmLiveTestFixture.baseConfig();
            wrongPwd.setPassword("M1T_wrong_password_for_leak_check");
            probe(wrongPwd);

            ConnectionConfig badSchema = DmLiveTestFixture.baseConfig();
            badSchema.setSchema("M1T_NO_SUCH_SCHEMA_FOR_LEAK");
            probe(badSchema);

            ConnectionConfig refused = DmLiveTestFixture.baseConfig();
            refused.setPort(25999);
            probe(refused);

            int after = countMySessions(conn);
            System.out.println("MATRIX|leakCheck|sessions before=" + before + " after=" + after);
            assertTrue(after <= before,
                    "session count grew after failed connects: before=" + before + " after=" + after);
        }
    }

    // ---------- helpers ----------

    private ProbeResult probe(ConnectionConfig config) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "dm-failure-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<ProbeResult> future = executor.submit(() -> {
                long start = System.nanoTime();
                try (Connection ignored = plugin.connect(config)) {
                    return new ProbeResult(null, elapsedMs(start));
                } catch (Throwable t) {
                    return new ProbeResult(t, elapsedMs(start));
                }
            });
            return future.get(TIMEOUT_WATCHDOG_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.out.println("MATRIX|WATCHDOG|connect still blocked after " + TIMEOUT_WATCHDOG_MS + "ms");
            return new ProbeResult(e, TIMEOUT_WATCHDOG_MS);
        } catch (Exception e) {
            return new ProbeResult(e, -1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void report(String scenario, ProbeResult result) {
        Throwable t = result.failure();
        SQLException sqlException = DmLiveTestFixture.findSqlException(t);
        String topClass = t == null ? "OK" : t.getClass().getName();
        StringBuilder causes = new StringBuilder();
        for (Throwable c = t == null ? null : t.getCause(); c != null; c = c.getCause()) {
            if (causes.length() > 0) {
                causes.append(" <- ");
            }
            causes.append(c.getClass().getSimpleName());
        }
        String sqlClass = sqlException == null ? "-" : sqlException.getClass().getName();
        String sqlState = sqlException == null ? "-" : String.valueOf(sqlException.getSQLState());
        String errorCode = sqlException == null ? "-" : String.valueOf(sqlException.getErrorCode());
        String message = t == null || t.getMessage() == null ? "-"
                : t.getMessage().replaceAll("[\\r\\n|]", " ");
        System.out.println("MATRIX|" + scenario + "|" + topClass + "|" + sqlClass + "|"
                + sqlState + "|" + errorCode + "|" + result.elapsedMs() + "ms|" + abbreviate(message, 160)
                + "|causes=" + causes);

        String realPassword = DmLiveTestFixture.setting("password");
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && realPassword != null) {
                assertTrue(!c.getMessage().contains(realPassword),
                        "failure message must not leak the real password: " + scenario);
            }
        }
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private int countMySessions(Connection conn) throws SQLException {
        String user = DmLiveTestFixture.baseConfig().getUsername();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM V$SESSIONS WHERE USER_NAME = '" + user + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Path tempDir() {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "dm-m1-matrix");
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path buildJarWithFakeDmDriver() throws IOException {
        Path workDir = Files.createTempDirectory("dm-m1-fakedriver");
        Path source = workDir.resolve("dm/jdbc/driver/DmDriver.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package dm.jdbc.driver; public class DmDriver { }");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int exit = compiler.run(null, null, null, "-d", workDir.toString(), source.toString());
        if (exit != 0) {
            throw new IllegalStateException("failed to compile fake DmDriver, javac exit=" + exit);
        }

        Path jar = tempDir().resolve(DmLiveTestRun.PREFIX_NAMESPACE + "wrongapi.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            Path classFile = workDir.resolve("dm/jdbc/driver/DmDriver.class");
            jos.putNextEntry(new JarEntry("dm/jdbc/driver/DmDriver.class"));
            Files.copy(classFile, jos);
            jos.closeEntry();
        }
        return jar;
    }
}
