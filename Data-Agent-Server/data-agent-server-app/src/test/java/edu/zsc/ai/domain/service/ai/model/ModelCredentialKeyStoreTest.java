package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.model.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary directories and synthetic secrets only; no process environment credentials are read. */
@Timeout(20)
class ModelCredentialKeyStoreTest {
    @TempDir Path temporary;
    final ModelAccessContext scope = new ModelAccessContext(1, WorkspaceTypeEnum.PERSONAL, 1);
    MultiProviderProperties properties;
    @BeforeEach void setup() { properties = properties(temporary); }
    static MultiProviderProperties properties(Path directory) {
        var properties = new MultiProviderProperties();
        properties.getModelRouting().setStorageDirectory(directory.toString());
        return properties;
    }
    static MockEnvironment environment(Map<String,Object> values) {
        return new MockEnvironment() { @Override public Map<String,Object> getSystemEnvironment() { return values; } };
    }
    ModelCredentialCipher cipher() { return new ModelCredentialCipher(environment(Map.of()), properties); }
    Path key() { return temporary.resolve(".keys/V1.key"); }

    @Test void firstUseGeneratesDurableOwnerOnlyKeyAndFreshCipherCanDecrypt() throws Exception {
        var first = cipher();
        assertTrue(first.configured());
        byte[] original = Files.readAllBytes(key());
        assertEquals(32, Base64.getDecoder().decode(original).length);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(key()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(key().getParent()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(key().resolveSibling("keys.lock")));
        String payload = first.encrypt("synthetic-provider-key", scope, "credential-one", "V1");
        assertFalse(payload.contains("synthetic-provider-key"));
        var restarted = cipher();
        assertTrue(restarted.configured());
        assertEquals("synthetic-provider-key", restarted.decrypt(payload, scope, "credential-one", "V1"));
        assertArrayEquals(original, Files.readAllBytes(key()));
        try (var files = Files.list(key().getParent())) { assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp"))); }
    }

    @Test void independentInstancesConcurrentlyInitializeOneKey() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        try {
            List<Future<String>> requests = new ArrayList<>();
            for (int i = 0; i < 12; i++) requests.add(executor.submit(() -> {
                start.await(); return cipher().encrypt("concurrent-fixture", scope, "credential", "V1");
            }));
            start.countDown();
            for (var result : requests) assertEquals("concurrent-fixture", cipher().decrypt(result.get(10, TimeUnit.SECONDS), scope, "credential", "V1"));
        } finally { executor.shutdownNow(); }
    }

    @Test void separateJvmsRaceToInitializeAndBothCiphertextsRemainReadable() throws Exception {
        Path firstResult = temporary.resolve("first.payload"), secondResult = temporary.resolve("second.payload");
        Process first = initializer(firstResult), second = initializer(secondResult);
        try {
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));assertEquals(0, first.exitValue(), "First fixture JVM failed");
            assertTrue(second.waitFor(10, TimeUnit.SECONDS));assertEquals(0, second.exitValue(), "Second fixture JVM failed");
            for (Path result : List.of(firstResult, secondResult)) {
                assertEquals("child-fixture", cipher().decrypt(Files.readString(result), scope, "credential", "V1"));
            }
        } finally { first.destroyForcibly();second.destroyForcibly(); }
    }
    Process initializer(Path result) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Initializer.class.getName(), temporary.toString(), result.toString()).redirectErrorStream(true).start();
    }
    public static class Initializer {
        public static void main(String[] args) throws Exception {
            var cipher = new ModelCredentialCipher(environment(Map.of()), properties(Path.of(args[0])));
            String payload = cipher.encrypt("child-fixture", new ModelAccessContext(1, WorkspaceTypeEnum.PERSONAL, 1), "credential", "V1");
            Files.writeString(Path.of(args[1]), payload);
        }
    }

    @Test void explicitEnvironmentKeyWinsWithoutCreatingOrReplacingLocalFiles() throws Exception {
        var external = new ModelCredentialCipher(environment(Map.of("MODEL_CREDENTIAL_KEY_V1", Base64.getEncoder().encodeToString(new byte[32]))), properties);
        String payload = external.encrypt("external-fixture", scope, "credential", "V1");
        assertTrue(external.configured());
        assertEquals("external-fixture", external.decrypt(payload, scope, "credential", "V1"));
        assertFalse(Files.exists(temporary.resolve(".keys")));
        assertTrue(cipher().configured());byte[] local = Files.readAllBytes(key());
        payload = external.encrypt("external-fixture", scope, "credential", "V1");
        String externalPayload = payload;
        assertThrows(ModelManagementException.class, () -> cipher().decrypt(externalPayload, scope, "credential", "V1"));
        assertEquals("external-fixture", external.decrypt(payload, scope, "credential", "V1"));
        assertArrayEquals(local, Files.readAllBytes(key()));
    }

    @Test void invalidExplicitKeyDoesNotFallBackToAnExistingLocalKey() throws Exception {
        assertTrue(cipher().configured());byte[] original = Files.readAllBytes(key());
        for (String invalid : List.of("", "invalid-fixture", Base64.getEncoder().encodeToString(new byte[16]))) {
            var broken = new ModelCredentialCipher(environment(Map.of("MODEL_CREDENTIAL_KEY_V1", invalid)), properties);
            assertFalse(broken.configured());
            var error = assertThrows(ModelManagementException.class, () -> broken.encrypt("secret", scope, "credential", "V1"));
            assertNull(error.getCause());assertFalse(error.toString().contains("invalid-fixture"));
            assertArrayEquals(original, Files.readAllBytes(key()));
        }
    }

    @Test void corruptOrDeletedLocalKeyIsNeverRegenerated() throws Exception {
        var cipher = cipher();assertTrue(cipher.configured());
        String payload = cipher.encrypt("fixture", scope, "credential", "V1");
        Files.writeString(key(), "corrupt-fixture");
        assertFalse(cipher().configured());
        assertThrows(ModelManagementException.class, () -> cipher().decrypt(payload, scope, "credential", "V1"));
        assertEquals("corrupt-fixture", Files.readString(key()));
        Files.delete(key());
        assertFalse(cipher().configured());
        assertThrows(ModelManagementException.class, () -> cipher().encrypt("fixture", scope, "credential", "V1"));
        assertFalse(Files.exists(key()));
    }

    @Test void decryptingMissingKeyNeverGeneratesOne() {
        var payload = Base64.getEncoder().encodeToString(new byte[29]);
        assertThrows(ModelManagementException.class, () -> cipher().decrypt(payload, scope, "credential", "V1"));
        assertFalse(Files.exists(temporary.resolve(".keys")));
    }

    @Test void preexistingEncryptedCredentialsRequireTheirOriginalKey() throws Exception {
        for (String workspace : List.of("PERSONAL", "ORGANIZATION")) {
            Path root = temporary.resolve(workspace.toLowerCase()), config = root.resolve(workspace + "/12/models.json");
            Files.createDirectories(config.getParent());
            String original = "{\"credentials\":{\"fixture\":{\"kind\":\"ENCRYPTED\",\"keyVersion\":\"V1\"}}}";
            Files.writeString(config, original);
            var missing = new ModelCredentialCipher(environment(Map.of()), properties(root));
            assertFalse(missing.configured());
            assertFalse(Files.exists(root.resolve(".keys/V1.key")));
            assertEquals(original, Files.readString(config));
            var newVersion = environment(Map.of()).withProperty("ai.model-credentials.active-key-version", "V2");
            assertTrue(new ModelCredentialCipher(newVersion, properties(root)).configured());
            assertFalse(missing.configured());assertFalse(Files.exists(root.resolve(".keys/V1.key")));
        }
    }

    @Test void newVersionCanBeGeneratedWhileOlderVersionRemainsDecryptable() throws Exception {
        var env = environment(Map.of());var cipher = new ModelCredentialCipher(env, properties);
        String old = cipher.encrypt("old-fixture", scope, "credential", "V1");
        env.withProperty("ai.model-credentials.active-key-version", "V2");assertTrue(cipher.configured());
        String current = cipher.encrypt("new-fixture", scope, "credential", "V2");
        assertEquals("old-fixture", cipher().decrypt(old, scope, "credential", "V1"));
        assertEquals("new-fixture", cipher().decrypt(current, scope, "credential", "V2"));
        assertFalse(Arrays.equals(Files.readAllBytes(key()), Files.readAllBytes(key().resolveSibling("V2.key"))));
    }

    @Test void symlinkedStorageKeyDirectoryKeyAndLockAreRejected() throws Exception {
        Path outside = temporary.resolve("outside");Files.createDirectory(outside);
        Path root = temporary.resolve("root-link");Files.createSymbolicLink(root, outside);
        assertFalse(new ModelCredentialCipher(environment(Map.of()), properties(root)).configured());
        root = temporary.resolve("directory-link");Files.createDirectory(root);Files.createSymbolicLink(root.resolve(".keys"), outside);
        assertFalse(new ModelCredentialCipher(environment(Map.of()), properties(root)).configured());
        Path sentinel = outside.resolve("sentinel");Files.writeString(sentinel, "unchanged-fixture");
        for (String name : List.of("V1.key", "keys.lock", "V1.initialized")) {
            root = temporary.resolve(name + "-case");Files.createDirectories(root.resolve(".keys"));
            Files.createSymbolicLink(root.resolve(".keys").resolve(name), sentinel);
            assertFalse(new ModelCredentialCipher(environment(Map.of()), properties(root)).configured());
            assertEquals("unchanged-fixture", Files.readString(sentinel));
        }
    }

    @Test void invalidStorageOrCorruptLegacyConfigurationDoesNotGenerateAKey() throws Exception {
        for (String path : List.of("", "/", "relative")) {
            var settings = new MultiProviderProperties();settings.getModelRouting().setStorageDirectory(path);
            assertFalse(new ModelCredentialCipher(environment(Map.of()), settings).configured());
        }
        Path config = temporary.resolve("PERSONAL/1/models.json");Files.createDirectories(config.getParent());
        Files.writeString(config, "corrupt-config-fixture");
        assertFalse(cipher().configured());assertFalse(Files.exists(key()));
        assertEquals("corrupt-config-fixture", Files.readString(config));
    }
}
