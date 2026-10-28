package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** All reads and writes share the stable lock file, across repository objects and JVM processes. */
final class LocalConnectionRepository {
    static final long MAX_NUMBER = 9_007_199_254_740_991L;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();
    private static final ReentrantLock[] MUTEXES = new ReentrantLock[128];
    static { Arrays.setAll(MUTEXES, ignored -> new ReentrantLock()); }
    final LocalPrivateFiles files;
    private final ThreadLocal<State> transaction = new ThreadLocal<>();

    LocalConnectionRepository(Path root) { files = new LocalPrivateFiles(root); }
    LocalConnectionRepository(LocalPrivateFiles files) { this.files = files; }

    <T> T locked(Function<State, T> action) {
        if (transaction.get() != null) return action.apply(transaction.get());
        ReentrantLock mutex = null;
        boolean held = false;
        try {
            files.directory(files.root);
            Path lockPath = files.root.resolve("connections.lock");
            // Canonical key prevents aliased parent directories bypassing the JVM lock.
            Path key = files.root.toRealPath().resolve("connections.lock");
            mutex = MUTEXES[Math.floorMod(key.hashCode(), MUTEXES.length)];
            if (!(held = mutex.tryLock(5, TimeUnit.SECONDS))) throw LocalRequestException.storeUnavailable();
            LocalPrivateFiles.rejectLink(lockPath);
            try (var channel = FileChannel.open(lockPath, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS), files.creationAttributes(files.root, false))) {
                files.protect(lockPath, false);
                try (var ignored = acquire(channel)) {
                    transaction.set(read());
                    return action.apply(transaction.get());
                } finally { transaction.remove(); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw LocalRequestException.storeUnavailable();
        } catch (IOException | UnsupportedOperationException e) { throw LocalRequestException.storeUnavailable(); }
        finally { if (held) mutex.unlock(); }
    }

    void save(State next) {
        if (transaction.get() == null) throw new IllegalStateException("Repository lock required");
        validate(next);
        try {
            byte[] bytes = JSON.writeValueAsBytes(next);
            if (bytes.length > MAX_BYTES) throw LocalRequestException.storeUnavailable();
            files.atomicWrite(files.root.resolve("connections.json"), bytes);
            transaction.set(next);
        } catch (IOException | UnsupportedOperationException e) { throw LocalRequestException.storeUnavailable(); }
    }

    private State read() throws IOException {
        Path path = files.root.resolve("connections.json");
        LocalPrivateFiles.rejectLink(path);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return new State(1, 0, 1, List.of());
        try {
            State state = JSON.readValue(files.read(path, MAX_BYTES), State.class);
            validate(state);
            return state;
        } catch (RuntimeException e) { throw LocalRequestException.storeUnavailable(); }
    }

    private static FileLock acquire(FileChannel channel) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            try {
                var lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) { /* A non-repository caller may hold the same JVM's OS lock. */ }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw LocalRequestException.storeUnavailable();
    }

    private static void validate(State state) {
        if (state == null || state.formatVersion != 1 || state.revision < 0 || state.revision > MAX_NUMBER
                || state.nextConnectionId < 1 || state.nextConnectionId > MAX_NUMBER
                || state.connections == null || state.connections.size() > 10000) throw LocalRequestException.storeUnavailable();
        Set<String> names = new HashSet<>();
        long previous = 0;
        for (var item : state.connections) {
            if (item == null || item.id() <= previous || item.id() >= state.nextConnectionId || item.version() < 1
                    || item.version() > MAX_NUMBER || item.createdAt() == null || item.updatedAt() == null
                    || item.updatedAt().isBefore(item.createdAt()) || !names.add(item.name())
                    || item.properties() == null || item.properties().keySet().stream().anyMatch(k -> !LocalConnectionValidation.publicProperty(k))) {
                throw LocalRequestException.storeUnavailable();
            }
            var input = new LocalConnectionInput();
            input.setName(item.name()); input.setDbType(item.dbType()); input.setHost(item.host()); input.setPort(item.port());
            input.setDatabase(item.database()); input.setUsername(item.username()); input.setDriverJarPath(item.driverJarPath());
            input.setTimeout(item.timeout()); input.setProperties(item.properties());
            try { LocalConnectionValidation.validate(input, false); LocalCredentialVault.validateRef(item.credentialRef()); }
            catch (RuntimeException e) { throw LocalRequestException.storeUnavailable(); }
            previous = item.id();
        }
    }

    record State(int formatVersion, long revision, long nextConnectionId, List<LocalConnectionRecord> connections) {
        State { connections = connections == null ? null : List.copyOf(connections); }
        LocalConnectionRecord require(long id) {
            return connections.stream().filter(c -> c.id() == id).findFirst().orElseThrow(LocalRequestException::notFound);
        }
        State replace(LocalConnectionRecord record, boolean create) {
            if (revision >= MAX_NUMBER || (create && nextConnectionId >= MAX_NUMBER)) throw LocalRequestException.storeUnavailable();
            var list = new ArrayList<>(connections); list.removeIf(c -> c.id() == record.id()); list.add(record);
            list.sort(Comparator.comparingLong(LocalConnectionRecord::id));
            return new State(1, revision + 1, create ? nextConnectionId + 1 : nextConnectionId, list);
        }
        State delete(long id) {
            if (revision >= MAX_NUMBER) throw LocalRequestException.storeUnavailable();
            return new State(1, revision + 1, nextConnectionId, connections.stream().filter(c -> c.id() != id).toList());
        }
    }
}
