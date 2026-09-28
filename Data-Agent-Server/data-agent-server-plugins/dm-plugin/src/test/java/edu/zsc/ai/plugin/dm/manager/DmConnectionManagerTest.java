package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.connection.ConnectionFailureException;
import edu.zsc.ai.plugin.driver.DriverLoader;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmConnectionManagerTest {

    @Test
    void closesConnectionWhenSchemaInitializationFails() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("schema denied", "3F000", -2103));

        ConnectionConfig config = new ConnectionConfig();
        config.setHost("localhost");
        config.setPort(5236);
        config.setSchema("NO_ACCESS");
        config.setDriverJarPath("unused.jar");
        DmConnectionManager manager = new DmConnectionManager(
                () -> "dm.jdbc.driver.DmDriver", () -> "jdbc:dm://%s:%d/%s", () -> 5236);

        ConnectionFailureException thrown;
        try (MockedStatic<DriverLoader> loader = mockStatic(DriverLoader.class);
             MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenReturn(connection);

            thrown = assertThrows(ConnectionFailureException.class, () -> manager.connect(config));
        }

        assertEquals(ConnectionFailureException.Category.SCHEMA_SWITCH, thrown.getCategory());
        assertEquals("3F000", thrown.getSqlState());
        assertEquals(-2103, thrown.getErrorCode());
        verify(connection).close();
    }

    @Test
    void driverLoadFailureIsClassified() {
        ConnectionConfig config = new ConnectionConfig();
        config.setHost("localhost");
        config.setPort(5236);
        config.setDriverJarPath("/nonexistent/driver.jar");
        DmConnectionManager manager = new DmConnectionManager(
                () -> "dm.jdbc.driver.DmDriver", () -> "jdbc:dm://%s:%d", () -> 5236);

        ConnectionFailureException thrown = assertThrows(ConnectionFailureException.class,
                () -> manager.connect(config));
        assertEquals(ConnectionFailureException.Category.DRIVER_LOAD, thrown.getCategory());
    }

    @Test
    void connectFailureIsClassifiedAndScrubbed() {
        ConnectionConfig config = new ConnectionConfig();
        config.setHost("localhost");
        config.setPort(5236);
        config.setUsername("SYSDBA");
        config.setPassword("s3cret");
        config.setDriverJarPath("unused.jar");
        DmConnectionManager manager = new DmConnectionManager(
                () -> "dm.jdbc.driver.DmDriver", () -> "jdbc:dm://%s:%d", () -> 5236);

        ConnectionFailureException thrown;
        // constructed up front: SQLException.<init> calls DriverManager.getLogWriter(),
        // which would hit the MockedStatic while the stubbing is being registered
        SQLException authFailure = new SQLException("auth failed for user SYSDBA s3cret", "22000", -2501);
        try (MockedStatic<DriverLoader> loader = mockStatic(DriverLoader.class);
             MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenThrow(authFailure);

            thrown = assertThrows(ConnectionFailureException.class, () -> manager.connect(config));
        }

        assertEquals(ConnectionFailureException.Category.AUTHENTICATION, thrown.getCategory());
        assertEquals("22000", thrown.getSqlState());
        assertEquals(-2501, thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("****"), "password must be scrubbed from the message");
        assertTrue(!thrown.getMessage().contains("s3cret"), "message must not contain the password");
    }

    @Test
    void classifyMapsLiveVerifiedDmCodes() {
        // verified against DM8 (DmJdbcDriver18 8.1.3.140), see M1 connection failure matrix
        assertEquals(ConnectionFailureException.Category.AUTHENTICATION,
                DmConnectionManager.classify("22000", -2501));
        assertEquals(ConnectionFailureException.Category.NETWORK,
                DmConnectionManager.classify("08S01", 6001));
        assertEquals(ConnectionFailureException.Category.TIMEOUT,
                DmConnectionManager.classify("22000", -608));
        assertEquals(ConnectionFailureException.Category.NETWORK,
                DmConnectionManager.classify("08001", 0));
        assertEquals(ConnectionFailureException.Category.AUTHENTICATION,
                DmConnectionManager.classify("28000", 0));
        assertEquals(ConnectionFailureException.Category.UNKNOWN,
                DmConnectionManager.classify("22000", -2106));
        assertEquals(ConnectionFailureException.Category.UNKNOWN,
                DmConnectionManager.classify(null, 0));
    }

    @Test
    void resetSessionStateRePinsSchemaAndClearsWarnings() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);

        new DmConnectionManager(() -> "dm.jdbc.driver.DmDriver", () -> "jdbc:dm://%s:%d", () -> 5236)
                .resetSessionState(connection, null, "M1_CERT");

        verify(statement).execute("SET SCHEMA \"M1_CERT\"");
        verify(statement).close();
        verify(connection).clearWarnings();
    }

    @Test
    void resetSessionStateSkipsSchemaSwitchWhenSchemaBlank() throws Exception {
        Connection connection = mock(Connection.class);

        new DmConnectionManager(() -> "dm.jdbc.driver.DmDriver", () -> "jdbc:dm://%s:%d", () -> 5236)
                .resetSessionState(connection, null, "  ");

        verify(connection, org.mockito.Mockito.never()).createStatement();
        verify(connection).clearWarnings();
    }
}
