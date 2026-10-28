package edu.zsc.ai.local;

import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.domain.event.ConnectionConfigurationChangedEvent;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.ConnectRequest;
import edu.zsc.ai.domain.service.db.ManagedDataSourceFactory;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.plugin.Plugin;
import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalConnectionRuntimeTest {
    @TempDir Path directory;
    LocalConnectionService service;
    Path driver;
    ManagedDataSourceFactory factory;
    ApplicationEventPublisher events;

    @BeforeEach void setup() throws Exception {
        driver = Files.write(directory.resolve("fixture.jar"), new byte[0]);
        factory = mock(ManagedDataSourceFactory.class); events = mock(ApplicationEventPublisher.class);
        service = new LocalConnectionService(new LocalConnectionRepository(directory.resolve("data")), factory, events);
        RequestContext.set(new LocalRuntimeIdentity().newRequestContext());
    }
    @AfterEach void close() { service.close(); RequestContext.clear(); }
    @Test void pinnedToolVersionIsCheckedAgainBeforeReadingOrOpeningTheDefinition() {
        var saved=service.save(LocalConnectionStoreTest.input("pinned",driver),true);
        LocalConnectionStoreTest.assertReason(() -> service.withExpectedVersion(saved.id(),saved.version(),() -> {
            var update=LocalConnectionStoreTest.input("changed-during-tool",driver);
            update.setConnectionId(saved.id());update.setExpectedVersion(saved.version());service.save(update,false);
            LocalConnectionStoreTest.assertReason(() -> service.getById(saved.id()),"CONNECTION_REVISION_CONFLICT");
            LocalConnectionStoreTest.assertReason(() -> service.openConnection(saved.id()),"CONNECTION_REVISION_CONFLICT");
            return null;
        }),"CONNECTION_REVISION_CONFLICT");
        verifyNoInteractions(factory);
        assertThat(service.getById(saved.id()).getName()).isEqualTo("changed-during-tool");
    }
    ConnectionManager manager() {
        var manager = mock(ConnectionManager.class, withSettings().extraInterfaces(Plugin.class));
        when(((Plugin) manager).getPluginId()).thenReturn("fixture-mysql"); return manager;
    }
    DataSource pool() throws Exception {
        var pool = mock(DataSource.class, withSettings().extraInterfaces(AutoCloseable.class));
        var jdbc = mock(Connection.class); when(jdbc.isValid(1)).thenReturn(true); when(pool.getConnection()).thenReturn(jdbc); return pool;
    }

    @Test void savedCredentialsReachExistingPoolFactoryAndUpdateClosesOldPoolAndNotifiesConsumers() throws Exception {
        var input = LocalConnectionStoreTest.input("runtime", driver); input.setPassword("saved-secret");
        input.setProperties(Map.of("sslKeyStorePassword", "tls-secret")); var saved = service.save(input, true);
        var manager = manager(); var plugins = mock(DefaultPluginManager.class); var pool = pool();
        when(plugins.getConnectionManagerByDbType("mysql")).thenReturn(List.of(manager));
        when(plugins.getConnectionManagerByPluginId("fixture-mysql")).thenReturn(manager);
        when(factory.create(eq(manager), any(), any())).thenReturn(pool);
        try (var mocked = mockStatic(DefaultPluginManager.class)) {
            mocked.when(DefaultPluginManager::getInstance).thenReturn(plugins);
            assertThat(service.openConnection(saved.id())).isTrue();
            var config = ArgumentCaptor.forClass(ConnectionConfig.class); verify(factory).create(eq(manager), config.capture(), any());
            assertThat(config.getValue().getPassword()).isEqualTo("saved-secret");
            assertThat(config.getValue().getProperties()).containsEntry("sslKeyStorePassword", "tls-secret");
            var update = LocalConnectionStoreTest.input("changed", driver); update.setConnectionId(saved.id()); update.setExpectedVersion(1L);
            var changed = service.save(update, false);
            verify((AutoCloseable) pool).close();
            assertThat(ActiveConnectionRegistry.getConnection(new DbContext(saved.id(), null, null))).isEmpty();
            verify(events).publishEvent(new ConnectionConfigurationChangedEvent(saved.id(), changed.version(), "UPDATED"));
        }
    }

    @Test void testCredentialsAreTransientAndCloseTheOneOffConnection() {
        var manager = manager(); var jdbc = mock(Connection.class); var plugins = mock(DefaultPluginManager.class);
        when(plugins.getConnectionManagerByDbType("mysql")).thenReturn(List.of(manager));
        when(manager.connect(any())).thenReturn(jdbc); when(manager.getDbmsInfo(jdbc)).thenReturn("fixture DB");
        when(manager.getDriverInfo(jdbc)).thenReturn("fixture driver");
        var request = ConnectRequest.builder().dbType("mysql").host("db.example.invalid").port(3306).username("fixture")
                .driverJarPath(driver.toString()).password("temporary-secret").build();
        try (var mocked = mockStatic(DefaultPluginManager.class)) {
            mocked.when(DefaultPluginManager::getInstance).thenReturn(plugins);
            assertThat(service.testConnection(request).getStatus().name()).isEqualTo("SUCCEEDED");
            verify(manager).closeConnection(jdbc);
            assertThat(directory.resolve("data")).doesNotExist();
        }
    }

    @Test void driverErrorsDoNotReturnExceptionTextContainingSecrets() {
        var manager = manager(); var plugins = mock(DefaultPluginManager.class);
        when(plugins.getConnectionManagerByDbType("mysql")).thenReturn(List.of(manager));
        when(manager.connect(any())).thenThrow(new RuntimeException("jdbc URL contains temporary-secret"));
        var request = ConnectRequest.builder().dbType("mysql").host("db.example.invalid").port(3306).username("fixture")
                .driverJarPath(driver.toString()).password("temporary-secret").build();
        try (var mocked = mockStatic(DefaultPluginManager.class)) {
            mocked.when(DefaultPluginManager::getInstance).thenReturn(plugins);
            assertThatThrownBy(() -> service.testConnection(request)).isInstanceOfSatisfying(LocalRequestException.class, e -> {
                assertThat(e.response().reasonCode()).isEqualTo("LOCAL_CONNECTION_FAILED");
                assertThat(e.getMessage()).doesNotContain("temporary-secret", "jdbc URL");
            });
        }
    }

    @Test void externallyChangedDefinitionInvalidatesPoolBeforeNextUse() throws Exception {
        var saved = service.save(LocalConnectionStoreTest.input("external", driver), true);
        var manager = manager(); var plugins = mock(DefaultPluginManager.class); var pool = pool();
        when(plugins.getConnectionManagerByDbType("mysql")).thenReturn(List.of(manager));
        when(plugins.getConnectionManagerByPluginId("fixture-mysql")).thenReturn(manager);
        when(factory.create(eq(manager), any(), any())).thenReturn(pool);
        try (var mocked = mockStatic(DefaultPluginManager.class)) {
            mocked.when(DefaultPluginManager::getInstance).thenReturn(plugins); service.openConnection(saved.id());
            // Simulate another process replacing the formal file: no in-process change event.
            var other = new LocalConnectionRepository(directory.resolve("data"));
            other.locked(state -> {
                var old = state.require(saved.id());
                other.save(state.replace(new LocalConnectionRecord(old.id(), 2, "external change", old.dbType(), old.host(), old.port(),
                        old.database(), old.username(), old.driverJarPath(), old.timeout(), old.properties(), old.credentialRef(), old.createdAt(), java.time.Instant.now()), false));
                return null;
            });
            service.assertReadable(saved.id());
            verify((AutoCloseable) pool).close();
            verify(events).publishEvent(new ConnectionConfigurationChangedEvent(saved.id(), 2L, "EXTERNAL_CHANGE"));
        }
    }

    @Test void oneOffTestCanUseSavedCredentialsWithoutChangingThem() throws Exception {
        var input = LocalConnectionStoreTest.input("saved-test", driver); input.setPassword("saved-secret");
        var saved = service.save(input, true);
        byte[] before = Files.readAllBytes(directory.resolve("data/connections.json"));
        var request = new LocalConnectionTestInput(); request.setConnectionId(saved.id()); request.setExpectedVersion(1L);
        request.setDbType("mysql"); request.setHost("db.example.invalid"); request.setPort(3306); request.setUsername("fixture");
        request.setDriverJarPath(driver.toString());
        var manager = manager(); var jdbc = mock(Connection.class); var plugins = mock(DefaultPluginManager.class);
        when(plugins.getConnectionManagerByDbType("mysql")).thenReturn(List.of(manager)); when(manager.connect(any())).thenReturn(jdbc);
        try (var mocked = mockStatic(DefaultPluginManager.class)) {
            mocked.when(DefaultPluginManager::getInstance).thenReturn(plugins);
            service.testConnection(request); request.setPassword("temporary-override"); service.testConnection(request);
            var configs = ArgumentCaptor.forClass(ConnectionConfig.class); verify(manager, times(2)).connect(configs.capture());
            assertThat(configs.getAllValues().stream().map(ConnectionConfig::getPassword)).containsExactly("saved-secret", "temporary-override");
        }
        assertThat(Files.readAllBytes(directory.resolve("data/connections.json"))).isEqualTo(before);
        assertThat(service.getById(saved.id()).getPassword()).isEqualTo("saved-secret");
    }

    @Test void openingAndMutatingAreSerializedSoAnOldPoolCannotBePublishedAfterInvalidation() throws Exception {
        var saved = service.save(LocalConnectionStoreTest.input("open-race", driver), true);
        var enteringFactory = new CountDownLatch(1); var release = new CountDownLatch(1); var pool = pool();
        when(factory.create(any(), any(), any())).thenAnswer(invocation -> {
            enteringFactory.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return pool;
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var open = workers.submit(() -> {
                RequestContext.set(new LocalRuntimeIdentity().newRequestContext());
                try { return service.openConnection(saved.id()); } finally { RequestContext.clear(); }
            });
            assertThat(enteringFactory.await(5, TimeUnit.SECONDS)).isTrue();
            var mutate = workers.submit(() -> {
                RequestContext.set(new LocalRuntimeIdentity().newRequestContext());
                try {
                    var change = LocalConnectionStoreTest.input("race-updated", driver); change.setConnectionId(saved.id()); change.setExpectedVersion(1L);
                    return service.save(change, false);
                } finally { RequestContext.clear(); }
            });
            release.countDown(); assertThat(open.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mutate.get(10, TimeUnit.SECONDS).version()).isEqualTo(2);
            verify((AutoCloseable) pool).close();
            assertThat(ActiveConnectionRegistry.getConnection(new DbContext(saved.id(), null, null))).isEmpty();
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void legacyServerAdapterStillDelegatesLookupToMybatis() {
        var mapper = mock(edu.zsc.ai.domain.mapper.db.DbConnectionMapper.class);
        var legacy = new edu.zsc.ai.domain.service.db.impl.DbConnectionServiceImpl(
                mock(edu.zsc.ai.domain.service.db.ConnectionAccessService.class),
                mock(edu.zsc.ai.domain.mapper.sys.SysOrganizationConnectionPermissionMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(legacy, "baseMapper", mapper);
        var expected = edu.zsc.ai.domain.model.entity.db.DbConnection.builder().id(7L).build();
        when(mapper.selectById(7L)).thenReturn(expected);
        assertThat(((edu.zsc.ai.domain.service.db.ConnectionDefinitionReader) legacy).getById(7L)).isSameAs(expected);
        verify(mapper).selectById(7L);
    }
}
