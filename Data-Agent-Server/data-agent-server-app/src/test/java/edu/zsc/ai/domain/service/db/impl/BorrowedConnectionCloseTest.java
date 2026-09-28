package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BorrowedConnectionCloseTest {

    private MockedStatic<DefaultPluginManager> pluginManagerStatic;
    private DefaultPluginManager pluginManager;

    @BeforeEach
    void setUp() {
        pluginManager = mock(DefaultPluginManager.class);
        pluginManagerStatic = mockStatic(DefaultPluginManager.class);
        pluginManagerStatic.when(DefaultPluginManager::getInstance).thenReturn(pluginManager);
    }

    @AfterEach
    void tearDown() {
        pluginManagerStatic.close();
    }

    @Test
    void close_resetsSessionStateBeforeReturningToPool() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        when(pluginManager.getConnectionManagerByPluginId("stub-plugin")).thenReturn(manager);
        Connection connection = mock(Connection.class);

        borrowed(connection).close();

        InOrder order = inOrder(manager, connection);
        order.verify(manager).resetSessionState(connection, "sales", "dbo");
        order.verify(connection).close();
    }

    @Test
    void close_evictsPhysicalConnectionWhenResetFails() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        when(pluginManager.getConnectionManagerByPluginId("stub-plugin")).thenReturn(manager);
        SQLException resetFailure = new SQLException("SET SCHEMA failed");
        org.mockito.Mockito.doThrow(resetFailure).when(manager).resetSessionState(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        Connection physical = mock(Connection.class);
        Connection proxy = mock(Connection.class);
        when(proxy.isWrapperFor(Connection.class)).thenReturn(true);
        when(proxy.unwrap(Connection.class)).thenReturn(physical);

        assertDoesNotThrow(() -> borrowed(proxy).close());

        // physical connection closed (evicted) instead of being returned for reuse
        verify(physical).close();
        // pool proxy still closed so Hikari can recycle the dead entry
        verify(proxy).close();
    }

    @Test
    void close_suppressesUnwrapFailureAndStillClosesProxy() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        when(pluginManager.getConnectionManagerByPluginId("stub-plugin")).thenReturn(manager);
        SQLException resetFailure = new SQLException("SET SCHEMA failed");
        org.mockito.Mockito.doThrow(resetFailure).when(manager).resetSessionState(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        Connection proxy = mock(Connection.class);
        when(proxy.isWrapperFor(Connection.class)).thenThrow(new SQLException("unwrap unsupported"));

        assertDoesNotThrow(() -> borrowed(proxy).close());

        org.junit.jupiter.api.Assertions.assertEquals(1, resetFailure.getSuppressed().length);
        verify(proxy).close();
    }

    @Test
    void close_returnsWithoutResetWhenManagerCannotBeResolved() throws Exception {
        when(pluginManager.getConnectionManagerByPluginId("stub-plugin"))
                .thenThrow(new IllegalArgumentException("No plugin found with ID: stub-plugin"));
        Connection connection = mock(Connection.class);

        assertDoesNotThrow(() -> borrowed(connection).close());

        verify(connection).close();
        verify(connection, never()).unwrap(Connection.class);
    }

    @Test
    void close_evictsViaHikariApiWhenPoolIsHikari() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        when(pluginManager.getConnectionManagerByPluginId("stub-plugin")).thenReturn(manager);
        org.mockito.Mockito.doThrow(new SQLException("SET SCHEMA failed"))
                .when(manager).resetSessionState(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        com.zaxxer.hikari.HikariDataSource hikari = mock(com.zaxxer.hikari.HikariDataSource.class);
        Connection proxy = mock(Connection.class);

        assertDoesNotThrow(() -> borrowed(proxy, hikari).close());

        // immediate pool-level eviction, no fragile physical-close fallback
        verify(hikari).evictConnection(proxy);
        verify(proxy, never()).unwrap(Connection.class);
        verify(proxy).close();
    }

    @Test
    void close_wrapsSqlExceptionFromProxyClose() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        when(pluginManager.getConnectionManagerByPluginId("stub-plugin")).thenReturn(manager);
        Connection connection = mock(Connection.class);
        org.mockito.Mockito.doThrow(new SQLException("close failed")).when(connection).close();

        assertThrows(RuntimeException.class, () -> borrowed(connection).close());
    }

    private ActiveConnectionRegistry.BorrowedConnection borrowed(Connection connection) {
        return borrowed(connection, mock(javax.sql.DataSource.class));
    }

    private ActiveConnectionRegistry.BorrowedConnection borrowed(Connection connection, javax.sql.DataSource dataSource) {
        return new ActiveConnectionRegistry.BorrowedConnection(connection, activeConnection(dataSource));
    }

    private ActiveConnectionRegistry.ActiveConnection activeConnection(javax.sql.DataSource dataSource) {
        return new ActiveConnectionRegistry.ActiveConnection(
                dataSource,
                1L,
                1L,
                42L,
                "stub",
                "stub-plugin",
                "sales",
                "dbo",
                LocalDateTime.now(),
                LocalDateTime.now(),
                WorkspaceTypeEnum.PERSONAL,
                null
        );
    }
}
