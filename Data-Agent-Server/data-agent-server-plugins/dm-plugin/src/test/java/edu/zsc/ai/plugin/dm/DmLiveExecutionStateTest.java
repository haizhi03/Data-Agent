package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.connection.ConnectionFailureException;
import edu.zsc.ai.plugin.dm.executor.DmSqlExecutor;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live DM8 verification of the M1 execution/transaction state model. Run with:
 * mvn test -pl data-agent-server-plugins/dm-plugin -Ddm.live=true -Ddm.password=... -Dtest=DmLiveExecutionStateTest
 *
 * <p>Creates only M1D_EXEC_* objects in the default schema and drops them afterwards.
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmLiveExecutionStateTest {

    private static final String DRIVER_JAR = System.getProperty("dm.driver.jar",
            System.getProperty("user.home") + "/.data-agent/drivers/dm/DmJdbcDriver18-8.1.3.140.jar");
    private static final String TABLE = "M1D_EXEC_T";
    private static final String TABLE_DDL = "M1D_EXEC_T2";

    private static String password;

    private final DmSqlExecutor executor = new DmSqlExecutor();

    @BeforeAll
    static void requirePassword() {
        password = System.getProperty("dm.password");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "dm.password system property required for live tests");
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            dropQuietly(statement, TABLE);
            dropQuietly(statement, TABLE_DDL);
            statement.execute("CREATE TABLE " + TABLE + " (ID INT)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            dropQuietly(statement, TABLE);
            dropQuietly(statement, TABLE_DDL);
        }
    }

    @Test
    void committedWriteReportsCommitted() throws Exception {
        try (Connection connection = connect()) {
            SqlCommandResult result = executor.executeCommand(
                    request(connection, "INSERT INTO " + TABLE + " VALUES (1)", true));

            assertTrue(result.isSuccess());
            assertEquals(StatementExecutionState.COMMITTED, result.getStatementState());
            assertEquals(TransactionOutcome.COMMITTED, result.getTransactionOutcome());
            assertEquals(1, countRows(connection, TABLE));
        }
    }

    @Test
    void failedWriteReportsFailedAndRolledBack() throws Exception {
        try (Connection connection = connect()) {
            SqlCommandResult result = executor.executeCommand(
                    request(connection, "INSERT INTO M1D_EXEC_NO_SUCH_TABLE VALUES (1)", true));

            assertFalse(result.isSuccess());
            assertEquals(StatementExecutionState.FAILED, result.getStatementState());
            assertEquals(TransactionOutcome.ROLLED_BACK, result.getTransactionOutcome());
        }
    }

    @Test
    void atomicStyleBatchRollbackRemovesEarlierDml() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            SqlCommandResult first = executor.executeCommand(
                    request(connection, "INSERT INTO " + TABLE + " VALUES (1)", false));
            SqlCommandResult second = executor.executeCommand(
                    request(connection, "INSERT INTO M1D_EXEC_NO_SUCH_TABLE VALUES (1)", false));
            assertTrue(first.isSuccess());
            assertFalse(second.isSuccess());
            assertEquals(StatementExecutionState.FAILED, second.getStatementState());

            connection.rollback();
            connection.setAutoCommit(true);

            // ATOMIC DML-only batch: rollback confirmed, first statement did not persist
            assertEquals(0, countRows(connection, TABLE));
        }
    }

    /**
     * DM8 DDL implicitly commits the pending transaction (live-verified on
     * dm8_20250924_rev288894). A batch mixing DDL and DML can therefore NOT be fully
     * rolled back: rows inserted before the DDL survive rollback(), and the table created
     * inside the transaction persists. The app layer must never report such a batch as
     * fully rolled back (batchState UNKNOWN).
     */
    @Test
    void ddlImplicitlyCommitsPendingTransaction() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            executor.executeCommand(request(connection, "INSERT INTO " + TABLE + " VALUES (1)", false));
            SqlCommandResult ddl = executor.executeCommand(
                    request(connection, "CREATE TABLE " + TABLE_DDL + " (ID INT)", false));
            assertTrue(ddl.isSuccess());
            connection.rollback();
            connection.setAutoCommit(true);

            // DM fact: the DML before the DDL was implicitly committed by the DDL
            assertEquals(1, countRows(connection, TABLE));
            assertTrue(tableExists(connection, TABLE_DDL));
        }
    }

    @Test
    void stepwiseStylePartialCommitPersistsSuccessfulStatements() throws Exception {
        try (Connection connection = connect()) {
            SqlCommandResult first = executor.executeCommand(
                    request(connection, "INSERT INTO " + TABLE + " VALUES (1)", true));
            SqlCommandResult second = executor.executeCommand(
                    request(connection, "INSERT INTO M1D_EXEC_NO_SUCH_TABLE VALUES (1)", true));
            SqlCommandResult third = executor.executeCommand(
                    request(connection, "INSERT INTO " + TABLE + " VALUES (3)", true));

            assertEquals(StatementExecutionState.COMMITTED, first.getStatementState());
            assertEquals(StatementExecutionState.FAILED, second.getStatementState());
            assertEquals(TransactionOutcome.ROLLED_BACK, second.getTransactionOutcome());
            assertEquals(StatementExecutionState.COMMITTED, third.getStatementState());
            assertEquals(2, countRows(connection, TABLE));
        }
    }

    @Test
    void statementTimeoutReportsTimedOut() throws Exception {
        try (Connection connection = connect()) {
            SqlCommandRequest request = request(connection,
                    "SELECT COUNT(*) FROM SYSOBJECTS a, SYSOBJECTS b, SYSOBJECTS c, SYSOBJECTS d", true);
            request.setTimeoutMs(1000);
            SqlCommandResult result = executor.executeCommand(request);

            assertFalse(result.isSuccess());
            assertEquals(StatementExecutionState.TIMED_OUT, result.getStatementState());
            // DM timeout: sqlState 22000, vendor code -608 (live-verified)
            assertEquals(-608, result.getErrorCode());
            assertEquals(TransactionOutcome.ROLLED_BACK, result.getTransactionOutcome());
        }
    }

    @Test
    void connectionFailuresAreClassified() {
        Dm8Plugin plugin = new Dm8Plugin();

        ConnectionConfig auth = baseConfig();
        auth.setPassword("definitely_wrong_password");
        ConnectionFailureException authFailure = assertThrows(ConnectionFailureException.class,
                () -> plugin.connect(auth));
        assertEquals(ConnectionFailureException.Category.AUTHENTICATION, authFailure.getCategory());
        assertEquals(-2501, authFailure.getErrorCode());
        assertFalse(authFailure.getMessage().contains("definitely_wrong_password"),
                "message must not leak the password");

        ConnectionConfig network = baseConfig();
        network.setPort(25999);
        ConnectionFailureException networkFailure = assertThrows(ConnectionFailureException.class,
                () -> plugin.connect(network));
        assertEquals(ConnectionFailureException.Category.NETWORK, networkFailure.getCategory());
        assertEquals(6001, networkFailure.getErrorCode());

        ConnectionConfig schema = baseConfig();
        schema.setSchema("M1D_EXEC_NO_SUCH_SCHEMA");
        ConnectionFailureException schemaFailure = assertThrows(ConnectionFailureException.class,
                () -> plugin.connect(schema));
        assertEquals(ConnectionFailureException.Category.SCHEMA_SWITCH, schemaFailure.getCategory());
        assertEquals(-2103, schemaFailure.getErrorCode());
    }

    private Connection connect() throws SQLException {
        Dm8Plugin plugin = new Dm8Plugin();
        return plugin.connect(baseConfig());
    }

    private ConnectionConfig baseConfig() {
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(System.getProperty("dm.host", "localhost"));
        config.setPort(Integer.getInteger("dm.port", 25236));
        config.setUsername(System.getProperty("dm.user", "SYSDBA"));
        config.setPassword(password);
        config.setDriverJarPath(DRIVER_JAR);
        return config;
    }

    private SqlCommandRequest request(Connection connection, String sql, boolean needTransaction) {
        SqlCommandRequest request = new SqlCommandRequest();
        request.setConnection(connection);
        request.setOriginalSql(sql);
        request.setExecuteSql(sql);
        request.setNeedTransaction(needTransaction);
        return request;
    }

    private int countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean tableExists(Connection connection, String table) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private void dropQuietly(Statement statement, String table) {
        try {
            statement.execute("DROP TABLE " + table);
        } catch (SQLException ignored) {
            // table did not exist
        }
    }
}
