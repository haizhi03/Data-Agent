package edu.zsc.ai.plugin.dm.fixture;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import org.junit.jupiter.api.Assumptions;

import java.sql.SQLException;

/**
 * Shared fixture for DM live tests.
 *
 * <p>Credentials are read only from system properties ({@code -Ddm.password=...})
 * or environment variables ({@code DM_PASSWORD}). When no password is supplied the
 * test is skipped via {@link Assumptions#assumeTrue(boolean, String)}. No real
 * credential ever has a default value in this repository.
 */
public final class DmLiveTestFixture {

    public static final String DRIVER_JAR = System.getProperty("dm.driver.jar",
            System.getProperty("user.home") + "/.data-agent/drivers/dm/DmJdbcDriver18-8.1.3.140.jar");

    private DmLiveTestFixture() {
        // utility class
    }

    /**
     * Resolve a live setting: system property {@code dm.<key>} wins, then
     * environment variable {@code DM_<KEY>}.
     */
    public static String setting(String key) {
        String sysProp = System.getProperty("dm." + key);
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }
        String env = System.getenv("DM_" + key.toUpperCase());
        return (env != null && !env.isBlank()) ? env : null;
    }

    /**
     * Skip the calling test unless a DM password was supplied through
     * {@code -Ddm.password} or {@code DM_PASSWORD}.
     */
    public static void assumeCredentials() {
        Assumptions.assumeTrue(setting("password") != null,
                "DM live credentials not supplied; set -Ddm.password or DM_PASSWORD to run live tests");
    }

    /**
     * Build a base {@link ConnectionConfig} from live settings. Skips the test
     * when credentials are missing. Host/port/user keep non-secret defaults.
     */
    public static ConnectionConfig baseConfig() {
        assumeCredentials();
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(defaultIfNull(setting("host"), "localhost"));
        String port = setting("port");
        config.setPort(port != null ? Integer.valueOf(port) : 25236);
        config.setUsername(defaultIfNull(setting("user"), "SYSDBA"));
        config.setPassword(setting("password"));
        config.setDriverJarPath(DRIVER_JAR);
        return config;
    }

    private static String defaultIfNull(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Unwrap the first {@link SQLException} in the cause chain, or null.
     */
    public static SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Diagnostic description of a failure: exception class, sqlState, errorCode,
     * message. Never includes credentials.
     */
    public static String describe(Throwable throwable) {
        if (throwable == null) {
            return "<no exception>";
        }
        SQLException sqlException = findSqlException(throwable);
        StringBuilder sb = new StringBuilder();
        sb.append(throwable.getClass().getName());
        if (sqlException != null) {
            sb.append(" sqlException=").append(sqlException.getClass().getName());
            sb.append(" sqlState=").append(sqlException.getSQLState());
            sb.append(" errorCode=").append(sqlException.getErrorCode());
        }
        String message = throwable.getMessage();
        if (message != null) {
            sb.append(" message=").append(message.length() > 200 ? message.substring(0, 200) : message);
        }
        return sb.toString();
    }
}
