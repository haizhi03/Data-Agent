package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.connection.ConnectionFailureException;
import edu.zsc.ai.plugin.connection.JdbcConnectionBuilder;
import edu.zsc.ai.plugin.dm.util.DmJdbcConnectionBuilder;
import edu.zsc.ai.plugin.driver.DriverLoader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class DmConnectionManager implements ConnectionManager {

    private static final Logger logger = Logger.getLogger(DmConnectionManager.class.getName());

    /** DM vendor error codes, verified live against DM8 (DmJdbcDriver18 8.1.3.140). */
    static final int DM_ERROR_CODE_AUTH_FAILURE = -2501;   // 用户名或密码错误 (sqlState 22000)
    static final int DM_ERROR_CODE_TIMEOUT = -608;         // 请求执行超时 (sqlState 22000)
    static final int DM_ERROR_CODE_NETWORK = 6001;         // 网络通信异常 (sqlState 08S01)

    /** Daemon executor backing Connection.setNetworkTimeout (driver aborts blocked reads on it). */
    private static final Executor NETWORK_TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "dm-network-timeout");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final JdbcConnectionBuilder connectionBuilder = new DmJdbcConnectionBuilder();
    private final Supplier<String> driverClassNameSupplier;
    private final Supplier<String> jdbcUrlTemplateSupplier;
    private final IntSupplier defaultPortSupplier;

    public DmConnectionManager(Supplier<String> driverClassNameSupplier,
                               Supplier<String> jdbcUrlTemplateSupplier,
                               IntSupplier defaultPortSupplier) {
        this.driverClassNameSupplier = driverClassNameSupplier;
        this.jdbcUrlTemplateSupplier = jdbcUrlTemplateSupplier;
        this.defaultPortSupplier = defaultPortSupplier;
    }

    @Override
    public Connection connect(ConnectionConfig config) {
        try {
            DriverLoader.loadDriver(config, driverClassNameSupplier.get());
        } catch (RuntimeException e) {
            throw new ConnectionFailureException(
                    ConnectionFailureException.Category.DRIVER_LOAD,
                    "Failed to load DM JDBC driver: " + e.getMessage(), e);
        }

        String jdbcUrl = connectionBuilder.buildUrl(
                config,
                jdbcUrlTemplateSupplier.get(),
                defaultPortSupplier.getAsInt()
        );
        Properties properties = connectionBuilder.buildProperties(config);
        String target = String.format("%s:%d/%s",
                config.getHost(),
                config.getPort() != null ? config.getPort() : defaultPortSupplier.getAsInt(),
                config.getDatabase() != null ? config.getDatabase() : "");

        Connection connection;
        try {
            connection = DriverManager.getConnection(jdbcUrl, properties);
        } catch (SQLException e) {
            ConnectionFailureException.Category category = classify(e.getSQLState(), e.getErrorCode());
            String errorMessage = String.format("Failed to connect to DM database at %s: %s",
                    target, sanitize(e.getMessage(), config));
            logger.severe(errorMessage);
            throw new ConnectionFailureException(category, e.getSQLState(), e.getErrorCode(), errorMessage, e);
        }

        applyNetworkTimeout(connection, config);

        try {
            applyCurrentSchema(connection, config);
        } catch (SQLException e) {
            closeAfterInitializationFailure(connection, e);
            String errorMessage = String.format("Failed to switch schema on DM database at %s: %s",
                    target, sanitize(e.getMessage(), config));
            logger.severe(errorMessage);
            throw new ConnectionFailureException(
                    ConnectionFailureException.Category.SCHEMA_SWITCH,
                    e.getSQLState(), e.getErrorCode(), errorMessage, e);
        }

        logger.info(String.format("Successfully connected to DM database at %s", target));
        return connection;
    }

    /**
     * Map a DM connection-phase SQLException to a failure category.
     * DM reuses generic sqlState 22000 for several causes, so vendor errorCode wins.
     */
    static ConnectionFailureException.Category classify(String sqlState, int errorCode) {
        return switch (errorCode) {
            case DM_ERROR_CODE_AUTH_FAILURE -> ConnectionFailureException.Category.AUTHENTICATION;
            case DM_ERROR_CODE_TIMEOUT -> ConnectionFailureException.Category.TIMEOUT;
            case DM_ERROR_CODE_NETWORK -> ConnectionFailureException.Category.NETWORK;
            default -> {
                if (sqlState != null) {
                    if (sqlState.startsWith("08")) {
                        yield ConnectionFailureException.Category.NETWORK;
                    }
                    if ("28000".equals(sqlState)) {
                        yield ConnectionFailureException.Category.AUTHENTICATION;
                    }
                }
                yield ConnectionFailureException.Category.UNKNOWN;
            }
        };
    }

    /**
     * Defense in depth: driver messages should never embed credentials, but scrub the
     * configured password if it ever appears.
     */
    private static String sanitize(String message, ConnectionConfig config) {
        if (message == null) {
            return "Unknown error";
        }
        String password = config.getPassword();
        if (password != null && !password.isEmpty()) {
            return message.replace(password, "****");
        }
        return message;
    }

    private void closeAfterInitializationFailure(Connection connection, Exception original) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException closeException) {
            original.addSuppressed(closeException);
        }
    }

    /**
     * Apply the configured socket-level network timeout (M1-09). A failure to set it
     * never fails the connect — the timeout is a safety net, not a requirement.
     */
    private void applyNetworkTimeout(Connection connection, ConnectionConfig config) {
        Integer networkTimeoutMs = config.getNetworkTimeoutMs();
        if (networkTimeoutMs == null || networkTimeoutMs <= 0) {
            return;
        }
        try {
            connection.setNetworkTimeout(NETWORK_TIMEOUT_EXECUTOR, networkTimeoutMs);
        } catch (Throwable e) {
            logger.warning(String.format("Could not set networkTimeout=%dms on DM connection: %s",
                    networkTimeoutMs, e.getMessage()));
        }
    }

    /**
     * DM equivalent of "use database": connections are pooled per (catalog, schema),
     * so a schema-scoped console must land on the right current schema.
     */
    private void applyCurrentSchema(Connection connection, ConnectionConfig config) throws SQLException {
        String schema = config.getSchema();
        if (schema == null || schema.isBlank()) {
            return;
        }
        String quoted = "\"" + schema.replace("\"", "\"\"") + "\"";
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA " + quoted);
        }
    }

    /**
     * Restore the session baseline before the connection goes back to the pool:
     * re-pin the current schema (user SQL may have drifted it with SET SCHEMA) and
     * clear accumulated warnings. DM has no catalog concept; catalog is ignored.
     */
    @Override
    public void resetSessionState(Connection connection, String catalog, String schema) throws SQLException {
        if (schema != null && !schema.isBlank()) {
            String quoted = "\"" + schema.replace("\"", "\"\"") + "\"";
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SCHEMA " + quoted);
            }
        }
        connection.clearWarnings();
    }

    @Override
    public boolean testConnection(ConnectionConfig config) {
        try {
            Connection connection = connect(config);
            if (connection != null && !connection.isClosed()) {
                closeConnection(connection);
                return true;
            }
            return false;
        } catch (ConnectionFailureException e) {
            logger.warning(String.format("Connection test failed [%s sqlState=%s errorCode=%s]: %s",
                    e.getCategory(), e.getSqlState(), e.getErrorCode(), e.getMessage()));
            return false;
        } catch (Exception e) {
            logger.warning(String.format("Connection test failed: %s", e.getMessage()));
            return false;
        }
    }

    @Override
    public void closeConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close database connection: " + e.getMessage(), e);
        }
    }
}
