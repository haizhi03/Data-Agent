package edu.zsc.ai.domain.service.db.impl;

import com.zaxxer.hikari.HikariDataSource;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.db.ConnectionPoolProperties;
import edu.zsc.ai.domain.service.db.ManagedDataSourceFactory;
import edu.zsc.ai.plugin.Plugin;
import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.driver.MavenCoordinates;
import edu.zsc.ai.plugin.enums.DbType;
import edu.zsc.ai.plugin.enums.PluginType;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies pool-return session restoration against a real {@link HikariDataSource} backed by
 * instrumented stub physical connections (no real database needed). The stub plugin is registered
 * into the real {@link DefaultPluginManager} so SPI resolution works from any thread
 * (Mockito static mocks are thread-scoped and cannot cover concurrent borrowers).
 */
class PooledConnectionReturnTest {

    private static final String POOL_SCHEMA = "dbo";
    private static final String STUB_PLUGIN_ID = "stub-plugin";

    private StubPlugin manager;
    private HikariDataSource dataSource;
    private ActiveConnectionRegistry.ActiveConnection active;

    @BeforeEach
    void setUp() throws Exception {
        manager = new StubPlugin();
        pluginMap().put(STUB_PLUGIN_ID, manager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            dataSource.close();
        }
        pluginMap().remove(STUB_PLUGIN_ID);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Plugin> pluginMap() throws Exception {
        Field field = DefaultPluginManager.class.getDeclaredField("pluginMap");
        field.setAccessible(true);
        return (Map<String, Plugin>) field.get(DefaultPluginManager.getInstance());
    }

    @Test
    void return_restoresDirtySessionBitsAndResetsSchemaViaSpi() throws Exception {
        dataSource = createPool(2);
        active = activeConnection(dataSource);

        ActiveConnectionRegistry.BorrowedConnection first = active.borrowConnection();
        Connection proxy = first.connection();
        assertTrue(proxy.getAutoCommit());
        StubPhysicalConnection physical = handlerOf(proxy);

        // dirty every session bit a borrower can touch
        proxy.setAutoCommit(false);
        proxy.setReadOnly(true);
        proxy.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        proxy.setSchema("DRIFTED");
        first.close();

        // SPI reset happened with the pool-key catalog/schema, on the same physical connection
        assertEquals(1, manager.resetCalls.size());
        assertEquals("sales", manager.resetCalls.get(0)[0]);
        assertEquals(POOL_SCHEMA, manager.resetCalls.get(0)[1]);
        assertFalse(physical.closed, "physical connection must survive a normal return");
        assertEquals(POOL_SCHEMA, physical.schema);
        assertTrue(physical.warningsCleared);

        // next borrower gets the same physical connection with all baselines restored
        try (ActiveConnectionRegistry.BorrowedConnection second = active.borrowConnection()) {
            Connection again = second.connection();
            assertTrue(again.getAutoCommit());
            assertFalse(again.isReadOnly());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, again.getTransactionIsolation());
            assertEquals(POOL_SCHEMA, again.getSchema());
            assertSame(physical.proxy(), again.unwrap(Connection.class));
        }
    }

    @Test
    void return_evictsConnectionWhenResetFails() throws Exception {
        dataSource = createPool(2);
        active = activeConnection(dataSource);

        manager.resetFailures.add(new SQLException("SET SCHEMA failed"));

        ActiveConnectionRegistry.BorrowedConnection first = active.borrowConnection();
        Connection firstProxy = first.connection();
        StubPhysicalConnection dead = handlerOf(firstProxy);
        firstProxy.setSchema("DRIFTED");
        first.close();

        // pool entry is synchronously evicted (removed from the bag); Hikari closes the physical
        // connection on its close executor, so the close itself is asynchronous
        org.awaitility.Awaitility.await("physical connection closed after eviction")
                .atMost(java.time.Duration.ofSeconds(10))
                .until(() -> dead.closed);

        // Hikari hands out a fresh physical connection instead of the dirty one
        try (ActiveConnectionRegistry.BorrowedConnection second = active.borrowConnection()) {
            assertTrue(second.connection().isValid(1));
            StubPhysicalConnection replacement = handlerOf(second.connection());
            assertNotSame(dead.proxy(), replacement.proxy());
            assertFalse(replacement.closed);
        }
        // live physical connections never exceed the pool maximum
        assertTrue(manager.created.stream().filter(c -> !c.closed).count() <= 2,
                "live physical connections exceed maximumPoolSize");
    }

    @Test
    void concurrentBorrowReturnKeepsPoolStableAndSchemaConsistent() throws Exception {
        dataSource = createPool(3);
        active = activeConnection(dataSource);

        int threads = 6;
        int iterations = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
                            Connection connection = borrowed.connection();
                            // simulate schema drift on every borrow
                            connection.setSchema("DRIFTED");
                            connection.setAutoCommit(false);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "concurrent borrow/return did not finish");
        executor.shutdownNow();
        if (failure.get() != null) {
            throw new AssertionError("concurrent borrow/return failed", failure.get());
        }

        assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
        assertTrue(dataSource.getHikariPoolMXBean().getTotalConnections() <= 3,
                "pool grew beyond maximumPoolSize: " + dataSource.getHikariPoolMXBean().getTotalConnections());
        long livePhysical = manager.created.stream().filter(c -> !c.closed).count();
        assertTrue(livePhysical <= 3, "live physical connections exceed maximumPoolSize: " + livePhysical);
        // Hikari closes its one initialization-check connection at startup (minimumIdle=0);
        // nothing else may be evicted on the happy path
        long closedPhysical = manager.created.stream().filter(c -> c.closed).count();
        assertTrue(closedPhysical <= 1, "unexpected physical connection eviction(s): " + closedPhysical);

        // after the dust settles a borrower still lands on the pool-key schema with clean baselines
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            assertEquals(POOL_SCHEMA, borrowed.connection().getSchema());
            assertTrue(borrowed.connection().getAutoCommit());
        }
    }

    private HikariDataSource createPool(int maxPoolSize) {
        ConnectionPoolProperties properties = new ConnectionPoolProperties();
        properties.setMaximumPoolSize(maxPoolSize);
        properties.setMinimumIdle(0);
        properties.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        HikariManagedDataSourceFactory factory = new HikariManagedDataSourceFactory(properties);

        ConnectionConfig config = new ConnectionConfig();
        config.setHost("stub");
        config.setSchema(POOL_SCHEMA);

        ManagedDataSourceFactory.ManagedDataSourceRequest request =
                new ManagedDataSourceFactory.ManagedDataSourceRequest(42L, "stub", "sales", POOL_SCHEMA);
        return (HikariDataSource) factory.create(manager, config, request);
    }

    private ActiveConnectionRegistry.ActiveConnection activeConnection(HikariDataSource ds) {
        return new ActiveConnectionRegistry.ActiveConnection(
                ds, 1L, 1L, 42L, "stub", "stub-plugin", "sales", POOL_SCHEMA,
                LocalDateTime.now(), LocalDateTime.now(), WorkspaceTypeEnum.PERSONAL, null);
    }

    /**
     * Resolve the stub handler behind a Hikari proxy. Note: Hikari closes its initialization-check
     * connection at startup when minimumIdle=0, so {@code created.get(0)} is not necessarily the
     * borrowed connection — always resolve by identity.
     */
    private StubPhysicalConnection handlerOf(Connection hikariProxy) throws SQLException {
        Connection physical = hikariProxy.unwrap(Connection.class);
        return manager.created.stream()
                .filter(c -> c.proxy() == physical)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stub handler found for borrowed connection"));
    }

    /**
     * Stub plugin + {@link ConnectionManager}: creates instrumented physical connections and
     * implements {@code resetSessionState} the way ADR M1 §3.8 specifies for schema-scoped plugins.
     */
    private static final class StubPlugin implements Plugin, ConnectionManager {

        final List<StubPhysicalConnection> created = new CopyOnWriteArrayList<>();
        final List<String[]> resetCalls = new CopyOnWriteArrayList<>();
        final Queue<SQLException> resetFailures = new ConcurrentLinkedQueue<>();

        @Override
        public Connection connect(ConnectionConfig config) {
            StubPhysicalConnection physical = new StubPhysicalConnection(config.getSchema());
            created.add(physical);
            return physical.proxy();
        }

        @Override
        public boolean testConnection(ConnectionConfig config) {
            return true;
        }

        @Override
        public void closeConnection(Connection connection) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }

        @Override
        public void resetSessionState(Connection connection, String catalog, String schema) throws SQLException {
            SQLException failure = resetFailures.poll();
            if (failure != null) {
                throw failure;
            }
            resetCalls.add(new String[]{catalog, schema});
            if (schema != null) {
                connection.setSchema(schema);
            }
            connection.clearWarnings();
        }

        @Override
        public String getPluginId() {
            return STUB_PLUGIN_ID;
        }

        @Override
        public String getDisplayName() {
            return "Stub Plugin";
        }

        @Override
        public String getVersion() {
            return "0.0.0";
        }

        @Override
        public DbType getDbType() {
            return DbType.MYSQL;
        }

        @Override
        public PluginType getPluginType() {
            return PluginType.SQL;
        }

        @Override
        public String getDescription() {
            return "test stub";
        }

        @Override
        public String getVendor() {
            return "test";
        }

        @Override
        public String getWebsite() {
            return "";
        }

        @Override
        public String getSupportMinVersion() {
            return "";
        }

        @Override
        public String getSupportMaxVersion() {
            return "";
        }

        @Override
        public MavenCoordinates getDriverMavenCoordinates(String driverVersion) {
            throw new IllegalArgumentException("no driver");
        }
    }

    /**
     * Instrumented physical JDBC connection backed by a dynamic proxy; tracks the session bits
     * Hikari and the reset SPI care about.
     */
    private static final class StubPhysicalConnection implements InvocationHandler {

        private final Connection proxy;
        volatile boolean closed;
        volatile boolean autoCommit = true;
        volatile boolean readOnly;
        volatile int transactionIsolation = Connection.TRANSACTION_READ_COMMITTED;
        volatile String catalog;
        volatile String schema;
        volatile boolean warningsCleared;

        StubPhysicalConnection(String schema) {
            this.schema = schema;
            this.proxy = (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, this);
        }

        Connection proxy() {
            return proxy;
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> { autoCommit = (Boolean) args[0]; yield null; }
                case "isReadOnly" -> readOnly;
                case "setReadOnly" -> { readOnly = (Boolean) args[0]; yield null; }
                case "getTransactionIsolation" -> transactionIsolation;
                case "setTransactionIsolation" -> { transactionIsolation = (Integer) args[0]; yield null; }
                case "getCatalog" -> catalog;
                case "setCatalog" -> { catalog = (String) args[0]; yield null; }
                case "getSchema" -> schema;
                case "setSchema" -> { schema = (String) args[0]; yield null; }
                case "isValid" -> !closed;
                case "isClosed" -> closed;
                case "close", "abort" -> { closed = true; yield null; }
                case "clearWarnings" -> { warningsCleared = true; yield null; }
                case "getWarnings" -> null;
                case "getNetworkTimeout" -> 0;
                case "setNetworkTimeout" -> null;
                case "unwrap" -> ((Class<?>) args[0]).isInstance(proxy) ? proxy : throwUnsupported(args[0]);
                case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                case "toString" -> "StubPhysicalConnection";
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object throwUnsupported(Object iface) throws SQLException {
            throw new SQLException("Unsupported unwrap target: " + iface);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == void.class) {
                return null;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0f;
            }
            if (type == double.class) {
                return 0d;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            return '\0';
        }
    }
}
