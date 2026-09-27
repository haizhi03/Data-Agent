package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.executor.DmSqlExecutor;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestFixture;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestRun;
import edu.zsc.ai.plugin.execution.StatementCancellationRegistry;
import edu.zsc.ai.plugin.execution.StatementCancellationRegistry.CancelOutcome;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live DM8 end-to-end verification of M1-09 timeout / cancellation / row-cap behavior.
 * Run with:
 * mvn test -pl data-agent-server-plugins/dm-plugin -Ddm.live=true -Ddm.password=... \
 *   -Dtest=DmLiveTimeoutCancellationTest
 *
 * <p>Creates only {@code M1F_} prefixed objects in the M1_CERT schema and drops them
 * afterwards. Evidence recorded in docs/certification/dm8/M1-timeout-cancellation.md.
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmLiveTimeoutCancellationTest {

    private static final String SCHEMA = "M1_CERT";
    private static final String NAMESPACE = "M1F_";
    /** 4-way cartesian over SYSOBJECTS: runs far longer than any timeout used here. */
    private static final String LONG_SQL =
            "SELECT COUNT(*) FROM SYSOBJECTS a, SYSOBJECTS b, SYSOBJECTS c, SYSOBJECTS d";

    private final DmSqlExecutor executor = new DmSqlExecutor();

    @BeforeAll
    static void requireCredentialsAndSchema() throws Exception {
        DmLiveTestFixture.assumeCredentials();
        try (Connection connection = connect(null); Statement statement = connection.createStatement()) {
            try {
                statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION SYSDBA");
            } catch (java.sql.SQLException alreadyExists) {
                // schema present from an earlier run; connect(SCHEMA) below verifies usability
            }
        }
        // verify the schema is actually usable
        try (Connection connection = connect(SCHEMA)) {
            assertTrue(connection.isValid(5));
        }
    }

    /**
     * End-to-end: timeoutMs → Statement.setQueryTimeout → DM vendor code -608 → TIMED_OUT.
     */
    @Test
    void queryTimeoutOnLongQueryReportsTimedOut() throws Exception {
        try (Connection connection = connect(SCHEMA)) {
            SqlCommandRequest request = request(connection, LONG_SQL);
            request.setTimeoutMs(1000);

            long started = System.currentTimeMillis();
            SqlCommandResult result = executor.executeCommand(request);
            long elapsed = System.currentTimeMillis() - started;

            assertFalse(result.isSuccess());
            assertEquals(StatementExecutionState.TIMED_OUT, result.getStatementState());
            // DM timeout: vendor code -608 请求执行超时, sqlState 22000 (live-verified)
            assertEquals(-608, result.getErrorCode());
            assertEquals("22000", result.getSqlState());
            assertTrue(elapsed < 30_000, "timeout must abort the query promptly, took " + elapsed + "ms");
        }
    }

    /**
     * End-to-end confirmed-cancel path: the executing statement is registered under its
     * execution id, registry.cancel() triggers Statement.cancel(), DM confirms with
     * -6515 操作被取消 → CANCELLED, and the connection stays usable.
     */
    @Test
    void statementCancelThroughRegistryReportsCancelled() throws Exception {
        StatementCancellationRegistry registry = StatementCancellationRegistry.getInstance();
        String executionId = "M1F-CANCEL-" + UUID.randomUUID();
        try (Connection connection = connect(SCHEMA)) {
            SqlCommandRequest request = request(connection, LONG_SQL);
            request.setExecutionId(executionId);
            AtomicReference<SqlCommandResult> ref = new AtomicReference<>();
            Thread worker = new Thread(() -> ref.set(executor.executeCommand(request)), "m1f-cancel-worker");
            worker.start();
            try {
                // wait until the statement is actually registered, then give the server a
                // moment to start executing (DM ignores a cancel that lands before execute)
                awaitTrue(() -> registry.isActive(executionId), 10_000);
                Thread.sleep(1_000);

                assertEquals(CancelOutcome.CANCEL_REQUESTED, registry.cancel(executionId));

                worker.join(60_000);
                assertFalse(worker.isAlive(), "cancelled statement must unblock the worker thread");
                SqlCommandResult result = ref.get();
                assertFalse(result.isSuccess());
                assertEquals(StatementExecutionState.CANCELLED, result.getStatementState());
                // DM confirmed cancel: vendor code -6515 操作被取消, sqlState 25000 (live-verified)
                assertEquals(-6515, result.getErrorCode());
                assertEquals("25000", result.getSqlState());
                assertEquals(TransactionOutcome.NONE, result.getTransactionOutcome());

                // the connection survives a statement cancel
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT 1 FROM DUAL")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            } finally {
                if (worker.isAlive()) {
                    worker.interrupt();
                }
            }
        }
    }

    @Test
    void cancelWithoutActiveStatementIsReportedFactually() {
        assertEquals(CancelOutcome.NO_ACTIVE_STATEMENT,
                StatementCancellationRegistry.getInstance().cancel("M1F-NEVER-REGISTERED-" + UUID.randomUUID()));
    }

    /**
     * Large-result-set protection: an 11_000-row table is read with an explicit
     * maxRows=500 cap (plus fetchSize hint) and with the executor default cap; both
     * must stop at the cap and mark truncated/limitApplied instead of materializing
     * every row into memory.
     */
    @Test
    void largeResultSetIsCappedAndMarkedTruncated() throws Exception {
        try (Connection connection = connect(SCHEMA);
             DmLiveTestRun run = DmLiveTestRun.start(connection, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String table = run.name("BIG");
            run.execQuietly("CREATE TABLE \"" + SCHEMA + "\".\"" + table + "\" (ID INT, PAD VARCHAR(50))");
            run.track("TABLE", table);
            run.execQuietly("INSERT INTO \"" + SCHEMA + "\".\"" + table + "\""
                    + " SELECT LEVEL, RPAD('x', 50, 'y') FROM DUAL CONNECT BY LEVEL <= 11000");
            assertEquals(11_000, countRows(connection, table));

            // explicit cap + fetch size hint
            SqlCommandRequest capped = request(connection, "SELECT * FROM \"" + SCHEMA + "\".\"" + table + "\"");
            capped.setMaxRows(500);
            capped.setFetchSize(100);
            SqlCommandResult cappedResult = executor.executeCommand(capped);
            assertTrue(cappedResult.isSuccess());
            assertEquals(500, cappedResult.getFetchRows());
            assertEquals(Boolean.TRUE, cappedResult.getTruncated());
            assertEquals(Boolean.TRUE, cappedResult.getLimitApplied());

            // default cap (DEFAULT_MAX_ROWS = 10_000) bounds memory on the full table
            SqlCommandResult defaultResult = executor.executeCommand(
                    request(connection, "SELECT * FROM \"" + SCHEMA + "\".\"" + table + "\""));
            assertTrue(defaultResult.isSuccess());
            assertEquals(10_000, defaultResult.getFetchRows());
            assertEquals(Boolean.TRUE, defaultResult.getTruncated());

            // below the cap: everything returned, marked not truncated
            SqlCommandRequest generous = request(connection,
                    "SELECT * FROM \"" + SCHEMA + "\".\"" + table + "\" WHERE ID <= 300");
            generous.setMaxRows(500);
            SqlCommandResult fullResult = executor.executeCommand(generous);
            assertEquals(300, fullResult.getFetchRows());
            assertEquals(Boolean.FALSE, fullResult.getTruncated());
        }
    }

    /**
     * networkTimeout capability: applied when configured (socket-level safety net for a
     * hung server), silently absent otherwise.
     */
    @Test
    void networkTimeoutIsAppliedWhenConfigured() throws Exception {
        ConnectionConfig configured = baseConfig(null);
        configured.setNetworkTimeoutMs(7_000);
        try (Connection connection = new Dm8Plugin().connect(configured)) {
            assertEquals(7_000, connection.getNetworkTimeout());
        }
        // unconfigured connections must still work
        try (Connection connection = connect(SCHEMA);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1 FROM DUAL")) {
            assertTrue(rs.next());
        }
    }

    private static Connection connect(String schema) {
        return new Dm8Plugin().connect(baseConfig(schema));
    }

    private static ConnectionConfig baseConfig(String schema) {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        config.setSchema(schema);
        return config;
    }

    private SqlCommandRequest request(Connection connection, String sql) {
        SqlCommandRequest request = new SqlCommandRequest();
        request.setConnection(connection);
        request.setOriginalSql(sql);
        request.setExecuteSql(sql);
        request.setNeedTransaction(false);
        return request;
    }

    private int countRows(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM \"" + SCHEMA + "\".\"" + table + "\"")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            Thread.sleep(20);
        }
    }
}
