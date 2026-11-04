package edu.zsc.ai.domain.service.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import edu.zsc.ai.config.ai.model.MultiProviderProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.core.env.ConfigurableEnvironment;

/** Deployment environment wins; otherwise use a durable, owner-only local key per version. */
final class ModelCredentialKeyStore {
    private static final ReentrantLock[] MUTEXES = new ReentrantLock[128];
    static { Arrays.setAll(MUTEXES, ignored -> new ReentrantLock()); }
    private final ConfigurableEnvironment environment;
    private final MultiProviderProperties properties;
    private final SecureRandom random = new SecureRandom();

    ModelCredentialKeyStore(ConfigurableEnvironment environment, MultiProviderProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    byte[] resolve(String version, boolean create) {
        if (version == null || !version.matches("[A-Z0-9_]{1,32}")) throw ModelManagementException.unavailable();
        Object external = environment.getSystemEnvironment().get("MODEL_CREDENTIAL_KEY_" + version);
        if (external != null) {
            if (!(external instanceof String encoded)) throw ModelManagementException.unavailable();
            return decode(encoded); // Invalid explicit configuration never falls back to a different key.
        }
        ReentrantLock mutex = null;
        boolean held = false;
        try {
            Path root = root(create);
            Path directory = root.resolve(".keys");
            secureDirectory(directory, create);
            Path lockPath = directory.resolve("keys.lock");
            mutex = MUTEXES[(lockPath.hashCode() & Integer.MAX_VALUE) % MUTEXES.length];
            if (!(held = mutex.tryLock(5, TimeUnit.SECONDS))) throw ModelManagementException.unavailable();
            rejectLink(lockPath);
            try (var channel = FileChannel.open(lockPath,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ownerOnly(lockPath, false);
                try (var ignored = acquire(channel)) {
                    Path key = directory.resolve(version + ".key");
                    Path marker = directory.resolve(version + ".initialized");
                    rejectLink(key);
                    rejectLink(marker);
                    if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] bytes = read(key);
                        try { markInitialized(marker); return bytes; }
                        catch (IOException | RuntimeException e) { Arrays.fill(bytes, (byte) 0); throw e; }
                    }
                    // Decryption never creates a key. A marker also prevents regeneration after deletion,
                    // even if the workspace JSON was temporarily moved or contains no remaining credentials.
                    if (!create || Files.exists(marker, LinkOption.NOFOLLOW_LINKS) || hasEncryptedCredentials(root, version)) {
                        throw ModelManagementException.unavailable();
                    }
                    byte[] bytes = new byte[32];
                    random.nextBytes(bytes);
                    try {
                        save(key, bytes);
                        markInitialized(marker);
                        syncDirectory(directory);
                        return bytes;
                    } catch (IOException | RuntimeException e) { Arrays.fill(bytes, (byte) 0); throw e; }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ModelManagementException.unavailable();
        } catch (IOException | RuntimeException e) {
            throw ModelManagementException.unavailable();
        } finally { if (held) mutex.unlock(); }
    }

    private Path root(boolean create) throws IOException {
        String configured = properties.getModelRouting().getStorageDirectory();
        if (configured == null || configured.isBlank()) throw ModelManagementException.unavailable();
        Path root = Path.of(configured).normalize();
        if (!root.isAbsolute() || root.getParent() == null) throw ModelManagementException.unavailable();
        secureDirectory(root, create);
        return root.toRealPath();
    }

    private static void secureDirectory(Path path, boolean create) throws IOException {
        rejectLink(path);
        if (create) Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw ModelManagementException.unavailable();
        ownerOnly(path, true);
    }

    private static byte[] read(Path key) throws IOException {
        if (!Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS)) throw ModelManagementException.unavailable();
        ownerOnly(key, false);
        byte[] encoded;
        try (var input = Files.newInputStream(key, LinkOption.NOFOLLOW_LINKS)) { encoded = input.readNBytes(129); }
        try {
            if (encoded.length > 128) throw ModelManagementException.unavailable();
            return decode(new String(encoded, java.nio.charset.StandardCharsets.US_ASCII).trim());
        } finally { Arrays.fill(encoded, (byte) 0); }
    }

    private static byte[] decode(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 32) return bytes;
            Arrays.fill(bytes, (byte) 0);
        } catch (IllegalArgumentException ignored) { }
        throw ModelManagementException.unavailable();
    }

    private static void save(Path target, byte[] key) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".credential-key-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        byte[] encoded = Base64.getEncoder().encode(key);
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            rejectLink(target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Arrays.fill(encoded, (byte) 0); Files.deleteIfExists(temporary); }
    }

    private static void markInitialized(Path marker) throws IOException {
        try { Files.createFile(marker, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (FileAlreadyExistsException e) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) throw e;
        }
        ownerOnly(marker, false);
    }

    /** Refuse to replace a missing environment key used by pre-existing configurations. */
    private static boolean hasEncryptedCredentials(Path root, String version) throws IOException {
        for (String scope : List.of("PERSONAL", "ORGANIZATION")) {
            Path directory = root.resolve(scope);
            rejectLink(directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw ModelManagementException.unavailable();
            try (var owners = Files.newDirectoryStream(directory)) {
                for (Path owner : owners) {
                    rejectLink(owner);
                    if (!Files.isDirectory(owner, LinkOption.NOFOLLOW_LINKS)) throw ModelManagementException.unavailable();
                    Path config = owner.resolve("models.json");
                    rejectLink(config);
                    if (!Files.exists(config, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) throw ModelManagementException.unavailable();
                    byte[] bytes;
                    try (var input = Files.newInputStream(config, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(FileModelStore.MAX_BYTES + 1); }
                    if (bytes.length > FileModelStore.MAX_BYTES) throw ModelManagementException.unavailable();
                    JsonNode data = ModelManagementJson.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), JsonNode.class);
                    if (data == null || !data.path("credentials").isObject()) throw ModelManagementException.unavailable();
                    for (JsonNode credential : data.path("credentials")) {
                        if ("ENCRYPTED".equals(credential.path("kind").asText())) {
                            if (!credential.path("keyVersion").isTextual()) throw ModelManagementException.unavailable();
                            if (version.equals(credential.path("keyVersion").asText())) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static FileLock acquire(FileChannel channel) throws IOException, InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            var lock = channel.tryLock();
            if (lock != null) return lock;
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        throw ModelManagementException.unavailable();
    }

    private static void syncDirectory(Path path) {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException ignored) { }
    }
    private static void ownerOnly(Path path, boolean directory) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    private static void rejectLink(Path path) {
        if (Files.isSymbolicLink(path)) throw ModelManagementException.unavailable();
    }
}
