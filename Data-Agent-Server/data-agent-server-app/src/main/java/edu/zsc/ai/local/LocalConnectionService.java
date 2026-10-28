package edu.zsc.ai.local;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.ConnectRequest;
import edu.zsc.ai.domain.model.dto.response.db.ConnectionTestResponse;
import edu.zsc.ai.domain.model.entity.db.DbConnection;
import edu.zsc.ai.domain.service.db.*;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.impl.ConnectionServiceImpl;
import edu.zsc.ai.domain.event.ConnectionConfigurationChangedEvent;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;

/** Local scope adapter and connection management; JDBC pools and plugin behavior stay in the existing service. */
final class LocalConnectionService implements ConnectionDefinitionReader, ConnectionAccessService, ConnectionService, AutoCloseable {
    private final LocalConnectionRepository repository;
    private final LocalCredentialVault vault;
    private final ConnectionService delegate;
    private final ApplicationEventPublisher events;
    private final Map<Long, Long> openedVersions = new ConcurrentHashMap<>();
    private final ThreadLocal<ExpectedVersion> expectedVersion = new ThreadLocal<>();
    private record ExpectedVersion(long id, long version) { }

    <T> T withExpectedVersion(long id, long version, java.util.function.Supplier<T> action) {
        var previous = expectedVersion.get(); expectedVersion.set(new ExpectedVersion(id, version));
        try { assertReadable(id); T result = action.get(); assertReadable(id); return result; }
        finally { if (previous == null) expectedVersion.remove(); else expectedVersion.set(previous); }
    }
    private void checkExpected(LocalConnectionRecord record) {
        var expected = expectedVersion.get();
        if (expected != null && (expected.id() != record.id() || expected.version() != record.version())) throw LocalRequestException.conflict();
    }

    LocalConnectionService(LocalConnectionRepository repository, ManagedDataSourceFactory factory, ApplicationEventPublisher events) {
        this.repository = repository;
        this.vault = new LocalCredentialVault(repository.files);
        this.delegate = new ConnectionServiceImpl(this, this, factory);
        this.events = events;
    }

    List<LocalConnectionRecord.PublicView> list() {
        assertWorkbenchApiAllowed();
        return repository.locked(state -> state.connections().stream().map(item -> {
            vault.read(item.id(), item.credentialRef());
            return item.publicView();
        }).toList());
    }

    LocalConnectionRecord.PublicView get(long id) {
        assertWorkbenchApiAllowed(); validateId(id);
        return repository.locked(state -> {
            var item = state.require(id); vault.read(id, item.credentialRef()); return item.publicView();
        });
    }

    LocalConnectionRecord.PublicView save(LocalConnectionInput input, boolean create) {
        assertWorkbenchApiAllowed();
        LocalConnectionValidation.validate(input, true);
        if (input.getCredentialAction() == null || (create && (input.getConnectionId() != null || input.getExpectedVersion() != null))
                || (!create && (input.getConnectionId() == null || input.getExpectedVersion() == null))) throw LocalRequestException.invalid();
        if (!create) {
            validateId(input.getConnectionId());
            if (input.getExpectedVersion() < 1) throw LocalRequestException.invalid();
        }
        return repository.locked(state -> {
            var old = create ? null : state.require(input.getConnectionId());
            if (old != null && old.version() != input.getExpectedVersion()) throw LocalRequestException.conflict();
            if (state.revision() >= LocalConnectionRepository.MAX_NUMBER || (create && state.nextConnectionId() >= LocalConnectionRepository.MAX_NUMBER)
                    || (old != null && old.version() >= LocalConnectionRepository.MAX_NUMBER)) throw LocalRequestException.storeUnavailable();
            String name = input.getName().trim();
            if (state.connections().stream().anyMatch(c -> c.name().equals(name) && (old == null || c.id() != old.id()))) {
                throw LocalRequestException.invalid();
            }
            long id = create ? state.nextConnectionId() : old.id();
            var previousSecret = old == null ? new LocalCredentialVault.Secret(null, Map.of()) : vault.read(id, old.credentialRef());
            Map<String, String> publicProperties = new TreeMap<>(), privateProperties = new TreeMap<>(previousSecret.properties());
            input.getProperties().forEach((key, value) -> {
                if (LocalConnectionValidation.publicProperty(key)) publicProperties.put(key, value);
                else privateProperties.put(key, value);
            });
            String password = input.getPassword();
            if (input.getCredentialAction() == LocalConnectionInput.CredentialAction.REMOVE) {
                if ((password != null && !password.isBlank()) || input.getProperties().keySet().stream()
                        .anyMatch(k -> !LocalConnectionValidation.publicProperty(k))) throw LocalRequestException.invalid();
                password = null; privateProperties.clear();
            } else if (password == null || password.isBlank()) password = previousSecret.password();
            var nextSecret = new LocalCredentialVault.Secret(password, privateProperties);
            String ref = nextSecret.empty() ? null : vault.create(id, nextSecret);
            Instant now = Instant.now();
            var item = new LocalConnectionRecord(id, create ? 1 : old.version() + 1, name, input.getDbType().toLowerCase(Locale.ROOT),
                    input.getHost(), input.getPort(), input.getDatabase(), input.getUsername(), input.getDriverJarPath(), input.getTimeout(),
                    publicProperties, ref, create ? now : old.createdAt(), now);
            // On an uncertain/failed commit keep the new credential as a possible orphan. Never delete a potentially published reference.
            repository.save(state.replace(item, create));
            if (!create) invalidate(id, item.version(), "UPDATED");
            if (old != null) vault.removeUnreferenced(old.credentialRef());
            return item.publicView();
        });
    }

    void delete(long id, Long expectedVersion) {
        assertWorkbenchApiAllowed(); validateId(id);
        if (expectedVersion == null || expectedVersion < 1) throw LocalRequestException.invalid();
        repository.locked(state -> {
            var old = state.require(id);
            if (old.version() != expectedVersion) throw LocalRequestException.conflict();
            repository.save(state.delete(id));
            invalidate(id, null, "DELETED");
            vault.removeUnreferenced(old.credentialRef());
            return null;
        });
    }

    @Override public DbConnection getById(Long id) {
        assertWorkbenchApiAllowed(); validateId(id);
        return repository.locked(state -> {
            var item = state.require(id);
            checkExpected(item);
            var secret = vault.read(id, item.credentialRef());
            var properties = new TreeMap<>(item.properties()); properties.putAll(secret.properties());
            try {
                return DbConnection.builder().id(id).userId(1L).name(item.name()).dbType(item.dbType()).host(item.host()).port(item.port())
                        .database(item.database()).username(item.username()).driverJarPath(item.driverJarPath()).timeout(item.timeout())
                        .password(secret.password()).properties(LocalConnectionRepository.JSON.writeValueAsString(properties))
                        .createdAt(LocalDateTime.ofInstant(item.createdAt(), ZoneOffset.UTC))
                        .updatedAt(LocalDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC)).build();
            } catch (java.io.IOException e) { throw LocalRequestException.storeUnavailable(); }
        });
    }

    @Override public DbConnection getOwnedById(Long id) { return getById(id); }
    @Override public boolean isOwner(long id, long userId) { return userId == 1 && canRead(id); }
    @Override public boolean canRead(long id) {
        assertWorkbenchApiAllowed(); validateId(id);
        return repository.locked(state -> state.connections().stream().anyMatch(c -> c.id() == id));
    }
    @Override public void assertReadable(long id) {
        assertWorkbenchApiAllowed(); validateId(id);
        repository.locked(state -> {
            var found = state.connections().stream().filter(c -> c.id() == id).findFirst();
            Long opened = openedVersions.get(id);
            if (opened != null && (found.isEmpty() || opened != found.get().version())) invalidate(id,
                    found.map(LocalConnectionRecord::version).orElse(null), "EXTERNAL_CHANGE");
            checkExpected(found.orElseThrow(LocalRequestException::notFound));
            return null;
        });
    }
    @Override public void assertWritableForCurrentWorkspace(long id) { assertReadable(id); }
    @Override public void assertWorkbenchApiAllowed() {
        var context = RequestContext.snapshot();
        if (context == null || !Objects.equals(context.getUserId(), 1L) || context.getWorkspaceType() != WorkspaceTypeEnum.PERSONAL
                || context.getOrgId() != null || context.getOrgRole() != null || context.getOrgUserRelId() != null) {
            throw LocalRequestException.forbidden();
        }
    }

    @Override public Boolean openConnection(Long id) { return openConnection(new DbContext(id, null, null)); }
    @Override public Boolean openConnection(DbContext db) {
        assertWorkbenchApiAllowed(); validateId(db.connectionId());
        // Serialization with mutations prevents an old definition publishing a pool after an update invalidated it.
        try { return repository.locked(state -> {
            assertReadable(db.connectionId());
            var result = delegate.openConnection(db);
            openedVersions.put(db.connectionId(), state.require(db.connectionId()).version());
            return result;
        }); } catch (LocalRequestException e) { throw e; }
        catch (RuntimeException e) { throw connectionFailure(); }
    }
    @Override public void closeConnection(Long id) {
        assertWorkbenchApiAllowed(); validateId(id);
        repository.locked(state -> { state.require(id); invalidate(id, null, "CLOSED"); return null; });
    }
    @Override public ConnectionTestResponse testConnection(ConnectRequest request) {
        assertWorkbenchApiAllowed();
        var input = new LocalConnectionInput();
        input.setName("temporary-test"); input.setDbType(request.getDbType()); input.setHost(request.getHost()); input.setPort(request.getPort());
        input.setDatabase(request.getDatabase()); input.setUsername(request.getUsername()); input.setDriverJarPath(request.getDriverJarPath());
        input.setTimeout(request.getTimeout()); input.setPassword(request.getPassword()); input.setProperties(request.getProperties());
        LocalConnectionValidation.validate(input, true);
        ConnectRequest effective = request;
        if (request instanceof LocalConnectionTestInput local && local.getConnectionId() != null) {
            validateId(local.getConnectionId());
            if (local.getExpectedVersion() == null || local.getExpectedVersion() < 1) throw LocalRequestException.invalid();
            effective = repository.locked(state -> {
                var saved = state.require(local.getConnectionId());
                if (saved.version() != local.getExpectedVersion()) throw LocalRequestException.conflict();
                var secret = vault.read(saved.id(), saved.credentialRef());
                var properties = new TreeMap<>(saved.properties()); properties.putAll(secret.properties()); properties.putAll(request.getProperties());
                return ConnectRequest.builder().dbType(request.getDbType()).host(request.getHost()).port(request.getPort())
                        .database(request.getDatabase()).username(request.getUsername()).driverJarPath(request.getDriverJarPath()).timeout(request.getTimeout())
                        .password(request.getPassword() == null || request.getPassword().isBlank() ? secret.password() : request.getPassword())
                        .properties(properties).build();
            });
        } else if (request instanceof LocalConnectionTestInput local && local.getExpectedVersion() != null) throw LocalRequestException.invalid();
        // The snapshot is resolved under lock; no lock is held during a one-off network test and nothing is saved.
        try { return delegate.testConnection(effective); }
        catch (RuntimeException e) { throw connectionFailure(); }
    }

    private static void validateId(Long id) {
        if (id == null || id < 1 || id > LocalConnectionRepository.MAX_NUMBER) throw LocalRequestException.invalid();
    }
    private void invalidate(long id, Long version, String reason) {
        openedVersions.remove(id);
        try { events.publishEvent(new ConnectionConfigurationChangedEvent(id, version, reason)); }
        finally { ActiveConnectionRegistry.closeAllConnections(id); }
    }
    @Override public void close() { new ArrayList<>(openedVersions.keySet()).forEach(id -> invalidate(id, null, "SHUTDOWN")); }
    private static LocalRequestException connectionFailure() {
        return new LocalRequestException(502, "LOCAL_CONNECTION_FAILED", "数据库连接失败，请检查连接配置与驱动");
    }
}
