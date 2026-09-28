package edu.zsc.ai.domain.service.db.impl;

import com.zaxxer.hikari.HikariDataSource;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.db.ConnectionPoolProperties;
import edu.zsc.ai.domain.service.db.ManagedDataSourceFactory;
import edu.zsc.ai.plugin.Plugin;
import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.Dm8Plugin;
import edu.zsc.ai.plugin.driver.MavenCoordinates;
import edu.zsc.ai.plugin.enums.DbType;
import edu.zsc.ai.plugin.enums.PluginType;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live DM8 test for pool-return session restoration. Run with:
 * {@code mvn test -pl data-agent-server-app -am -Ddm.live=true -Ddm.password=... -Dtest=DmPooledConnectionReturnLiveTest}
 *
 * <p>The DM plugin's own {@code resetSessionState} is delivered by another workstream (ADR M1 §3.8,
 * dm-plugin column). Until it lands, this test registers a wrapper plugin implementing the exact
 * reset semantics the ADR specifies ({@code SET SCHEMA "<schema>"} + {@code clearWarnings()}),
 * so the app-side mechanism is verified end-to-end against a real DM instance.
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmPooledConnectionReturnLiveTest {

    private static final String POOL_SCHEMA = "SYSDBA";
    private static final String DRIFT_SCHEMA = "SYS";

    private DmResettingPlugin wrapper;
    private Plugin replacedPlugin;
    private HikariDataSource dataSource;
    private ActiveConnectionRegistry.ActiveConnection active;

    @BeforeAll
    static void requireCredentials() {
        Assumptions.assumeTrue(System.getProperty("dm.password") != null,
                "dm.password system property not provided; skipping live DM test");
    }

    @BeforeEach
    void setUp() throws Exception {
        wrapper = new DmResettingPlugin();
        replacedPlugin = pluginMap().put(wrapper.getPluginId(), wrapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            dataSource.close();
        }
        if (replacedPlugin != null) {
            pluginMap().put(wrapper.getPluginId(), replacedPlugin);
        } else {
            pluginMap().remove(wrapper.getPluginId());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Plugin> pluginMap() throws Exception {
        Field field = DefaultPluginManager.class.getDeclaredField("pluginMap");
        field.setAccessible(true);
        return (Map<String, Plugin>) field.get(DefaultPluginManager.getInstance());
    }

    @Test
    void return_restoresSchemaOnSamePhysicalConnection() throws Exception {
        dataSource = createPool(2);
        active = activeConnection(dataSource);

        Connection firstPhysical;
        try (ActiveConnectionRegistry.BorrowedConnection first = active.borrowConnection()) {
            Connection proxy = first.connection();
            assertEquals(POOL_SCHEMA, currentSchema(proxy));
            // user drifts the session schema
            executeSetSchema(proxy, DRIFT_SCHEMA);
            assertEquals(DRIFT_SCHEMA, currentSchema(proxy));
            firstPhysical = proxy.unwrap(Connection.class);
        }

        assertEquals(1, wrapper.resetCount.get(), "return must trigger SPI session reset");

        try (ActiveConnectionRegistry.BorrowedConnection second = active.borrowConnection()) {
            Connection proxy = second.connection();
            // schema restored on the SAME physical connection (not a freshly created one)
            assertEquals(POOL_SCHEMA, currentSchema(proxy));
            assertSame(firstPhysical, proxy.unwrap(Connection.class));
        }
    }

    @Test
    void concurrentBorrowReturnKeepsPoolStableAndSchemaConsistent() throws Exception {
        dataSource = createPool(3);
        active = activeConnection(dataSource);

        int threads = 6;
        int iterations = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
                            executeSetSchema(borrowed.connection(), DRIFT_SCHEMA);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(120, TimeUnit.SECONDS), "concurrent borrow/return did not finish");
        executor.shutdownNow();
        if (failure.get() != null) {
            throw new AssertionError("concurrent borrow/return failed", failure.get());
        }

        assertEquals(threads * iterations, wrapper.resetCount.get());
        assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
        int total = dataSource.getHikariPoolMXBean().getTotalConnections();
        assertTrue(total <= 3, "pool grew beyond maximumPoolSize: " + total);
        assertTrue(wrapper.connectCount.get() <= 4,
                "physical connections kept growing: " + wrapper.connectCount.get());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            assertEquals(POOL_SCHEMA, currentSchema(borrowed.connection()));
        }
    }

    private String currentSchema(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        assertTrue(schema != null && !schema.isBlank(), "driver returned no current schema");
        return schema.toUpperCase();
    }

    private void executeSetSchema(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
        }
    }

    private HikariDataSource createPool(int maxPoolSize) {
        ConnectionPoolProperties properties = new ConnectionPoolProperties();
        properties.setMaximumPoolSize(maxPoolSize);
        properties.setMinimumIdle(0);
        HikariManagedDataSourceFactory factory = new HikariManagedDataSourceFactory(properties);

        ManagedDataSourceFactory.ManagedDataSourceRequest request =
                new ManagedDataSourceFactory.ManagedDataSourceRequest(9001L, "dm", null, POOL_SCHEMA);
        return (HikariDataSource) factory.create(wrapper, dmConfig(), request);
    }

    private static ConnectionConfig dmConfig() {
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(System.getProperty("dm.host", "127.0.0.1"));
        config.setPort(Integer.getInteger("dm.port", 25236));
        config.setUsername(System.getProperty("dm.user", "SYSDBA"));
        config.setPassword(System.getProperty("dm.password"));
        config.setSchema(POOL_SCHEMA);
        config.setDriverJarPath(System.getProperty("dm.driver.jar",
                System.getProperty("user.home") + "/.data-agent/drivers/dm/DmJdbcDriver18-8.1.3.140.jar"));
        return config;
    }

    private ActiveConnectionRegistry.ActiveConnection activeConnection(HikariDataSource ds) {
        return new ActiveConnectionRegistry.ActiveConnection(
                ds, 1L, 1L, 9001L, "dm", wrapper.getPluginId(), null, POOL_SCHEMA,
                LocalDateTime.now(), LocalDateTime.now(), WorkspaceTypeEnum.PERSONAL, null);
    }

    /**
     * Wraps the real {@link Dm8Plugin}; implements {@code resetSessionState} exactly as ADR M1 §3.8
     * specifies for dm-plugin (SET SCHEMA + clearWarnings; DM has no catalog concept).
     */
    private static final class DmResettingPlugin implements Plugin, ConnectionManager {

        private final Dm8Plugin delegate = new Dm8Plugin();
        final AtomicInteger connectCount = new AtomicInteger();
        final AtomicInteger resetCount = new AtomicInteger();

        @Override
        public Connection connect(ConnectionConfig config) {
            connectCount.incrementAndGet();
            return delegate.connect(config);
        }

        @Override
        public boolean testConnection(ConnectionConfig config) {
            return delegate.testConnection(config);
        }

        @Override
        public void closeConnection(Connection connection) {
            delegate.closeConnection(connection);
        }

        @Override
        public void resetSessionState(Connection connection, String catalog, String schema) throws SQLException {
            resetCount.incrementAndGet();
            if (schema != null && !schema.isBlank()) {
                String quoted = "\"" + schema.replace("\"", "\"\"") + "\"";
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET SCHEMA " + quoted);
                }
            }
            connection.clearWarnings();
        }

        @Override
        public String getPluginId() {
            return delegate.getPluginId();
        }

        @Override
        public String getDisplayName() {
            return delegate.getDisplayName();
        }

        @Override
        public String getVersion() {
            return delegate.getVersion();
        }

        @Override
        public DbType getDbType() {
            return delegate.getDbType();
        }

        @Override
        public PluginType getPluginType() {
            return delegate.getPluginType();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public String getVendor() {
            return delegate.getVendor();
        }

        @Override
        public String getWebsite() {
            return delegate.getWebsite();
        }

        @Override
        public String getSupportMinVersion() {
            return delegate.getSupportMinVersion();
        }

        @Override
        public String getSupportMaxVersion() {
            return delegate.getSupportMaxVersion();
        }

        @Override
        public MavenCoordinates getDriverMavenCoordinates(String driverVersion) {
            return delegate.getDriverMavenCoordinates(driverVersion);
        }
    }
}
