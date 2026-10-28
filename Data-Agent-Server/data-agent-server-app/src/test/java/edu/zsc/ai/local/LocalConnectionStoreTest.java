package edu.zsc.ai.local;

import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.domain.service.db.ManagedDataSourceFactory;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalConnectionStoreTest {
    @TempDir Path directory;
    Path root, driver;
    LocalConnectionRepository repository;
    LocalConnectionService service;

    @BeforeEach void setup() throws Exception {
        root = directory.resolve("data"); driver = Files.write(directory.resolve("fixture-driver.jar"), new byte[0]);
        repository = new LocalConnectionRepository(root);
        service = service(repository);
        RequestContext.set(new LocalRuntimeIdentity().newRequestContext());
    }
    @AfterEach void clear() { service.close(); RequestContext.clear(); }
    static LocalConnectionService service(LocalConnectionRepository repository) {
        return new LocalConnectionService(repository, mock(ManagedDataSourceFactory.class), event -> {});
    }
    LocalConnectionInput input(String name) { return input(name, driver); }
    static LocalConnectionInput input(String name, Path driver) {
        var input = new LocalConnectionInput(); input.setName(name); input.setDbType("mysql"); input.setHost("db.example.invalid");
        input.setPort(3306); input.setUsername("fixture_user"); input.setDriverJarPath(driver.toString()); return input;
    }
    LocalConnectionInput update(LocalConnectionRecord.PublicView old) {
        var input = input(old.name()); input.setConnectionId(old.id()); input.setExpectedVersion(old.version()); return input;
    }
    static void assertReason(Runnable action, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LocalRequestException.class,
                error -> assertThat(error.response().reasonCode()).isEqualTo(reason));
    }

    @Test void emptyStoreAndRestartPreserveStableIdsAndUtcTimes() {
        assertThat(service.list()).isEmpty();
        assertThat(root.resolve("connections.json")).doesNotExist();
        var one = service.save(input("first"), true);
        assertThat(one.id()).isEqualTo(1); assertThat(one.version()).isEqualTo(1);
        assertThat(service(new LocalConnectionRepository(root)).get(1).createdAt()).isEqualTo(one.createdAt());
        service.delete(1, 1L);
        var next = service(new LocalConnectionRepository(root)).save(input("second"), true);
        assertThat(next.id()).isEqualTo(2);
        repository.locked(state -> { assertThat(state.revision()).isEqualTo(3); assertThat(state.nextConnectionId()).isEqualTo(3); return null; });
    }

    @Test void secretsAndUnknownPropertiesAreEncryptedAndReadableOnlyThroughInternalPort() throws Exception {
        var input = input("protected"); input.setPassword("fixture-db-password-8X");
        input.setProperties(Map.of("useSSL", "true", "sslKeyStorePassword", "fixture-tls-secret", "vendorOption", "fixture-private-option"));
        var view = service.save(input, true);
        assertThat(view.credentialConfigured()).isTrue();
        assertThat(view.properties()).containsExactlyEntriesOf(Map.of("useSSL", "true"));
        String publicJson = LocalConnectionRepository.JSON.writeValueAsString(view);
        assertThat(publicJson).doesNotContain("credentialRef", "password", "fixture-tls-secret", "fixture-private-option");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("master.key")).toList()) {
                assertThat(Files.readString(path)).doesNotContain("fixture-db-password-8X", "fixture-tls-secret", "fixture-private-option");
            }
        }
        var internal = service(new LocalConnectionRepository(root)).getById(view.id());
        assertThat(internal.getPassword()).isEqualTo("fixture-db-password-8X");
        assertThat(internal.getProperties()).contains("fixture-tls-secret", "fixture-private-option");
    }

    @Test void blankPasswordKeepsCredentialReplacementRotatesReferenceAndRemoveClearsAllPrivateFields() {
        var input = input("password"); input.setPassword("first-secret"); input.setProperties(Map.of("vendorSecret", "old-option"));
        var first = service.save(input, true);
        var keep = update(first); keep.setPassword("");
        var second = service.save(keep, false);
        assertThat(service.getById(first.id()).getPassword()).isEqualTo("first-secret");
        assertThat(service.getById(first.id()).getProperties()).contains("old-option");
        var replace = update(second); replace.setPassword("second-secret");
        var third = service.save(replace, false);
        assertThat(service.getById(first.id()).getPassword()).isEqualTo("second-secret");
        var remove = update(third); remove.setCredentialAction(LocalConnectionInput.CredentialAction.REMOVE);
        var fourth = service.save(remove, false);
        assertThat(fourth.credentialConfigured()).isFalse();
        assertThat(service.getById(first.id()).getPassword()).isNull();
        assertThat(service.getById(first.id()).getProperties()).isEqualTo("{}");
    }

    @Test void staleUpdatesAndDeletesDoNotOverwriteOrRemoveTheNewVersion() {
        var first = service.save(input("versioned"), true);
        var fresh = update(first); fresh.setName("new name"); service.save(fresh, false);
        assertReason(() -> service.save(update(first), false), "CONNECTION_REVISION_CONFLICT");
        assertReason(() -> service.delete(first.id(), first.version()), "CONNECTION_REVISION_CONFLICT");
        assertThat(service.get(first.id()).name()).isEqualTo("new name");
    }

    @Test void duplicateNamesAndContradictoryCredentialActionsAreRejected() {
        var first = service.save(input("duplicate"), true);
        assertReason(() -> service.save(input("duplicate"), true), "LOCAL_REQUEST_INVALID");
        var remove = update(first); remove.setCredentialAction(LocalConnectionInput.CredentialAction.REMOVE); remove.setPassword("secret");
        assertReason(() -> service.save(remove, false), "LOCAL_REQUEST_INVALID");
        var masked = update(first); masked.setPassword("******");
        assertReason(() -> service.save(masked, false), "LOCAL_REQUEST_INVALID");
        assertReason(() -> service.delete(first.id(), null), "LOCAL_REQUEST_INVALID");
        assertReason(() -> service.get(99), "CONNECTION_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"{", "{\"formatVersion\":2}", "{\"formatVersion\":1,\"revision\":0,\"nextConnectionId\":1,\"connections\":[],\"unknown\":true}",
            "{\"formatVersion\":1,\"revision\":0,\"nextConnectionId\":1,\"connections\":[]} {}"})
    void corruptedOrUnknownFilesArePreservedAndNeverReinitialized(String corrupt) throws Exception {
        Files.createDirectories(root); Path path = Files.writeString(root.resolve("connections.json"), corrupt);
        assertReason(() -> service.list(), "LOCAL_STORE_UNAVAILABLE");
        assertReason(() -> service.save(input("cannot replace corrupt file"), true), "LOCAL_STORE_UNAVAILABLE");
        assertThat(Files.readString(path)).isEqualTo(corrupt);
    }

    @Test void missingKeyIsNeverRegeneratedAndExistingCiphertextIsUntouched() throws Exception {
        var input = input("key-loss"); input.setPassword("fixture-secret"); service.save(input, true);
        byte[] config = Files.readAllBytes(root.resolve("connections.json"));
        Files.delete(root.resolve(".credentials/master.key"));
        assertReason(() -> service(new LocalConnectionRepository(root)).list(), "LOCAL_STORE_UNAVAILABLE");
        var replacement = input("another"); replacement.setPassword("another-secret");
        assertReason(() -> service.save(replacement, true), "LOCAL_STORE_UNAVAILABLE");
        assertThat(root.resolve(".credentials/master.key")).doesNotExist();
        assertThat(Files.readAllBytes(root.resolve("connections.json"))).isEqualTo(config);
    }

    @Test void replacingMasterKeyOrSwappingCredentialReferencesFailsAuthentication() throws Exception {
        var first = input("one"); first.setPassword("one-secret"); service.save(first, true);
        var second = input("two"); second.setPassword("two-secret"); service.save(second, true);
        Path config = root.resolve("connections.json");
        var tree = LocalConnectionRepository.JSON.readTree(Files.readAllBytes(config));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("connections").get(0)).put("credentialRef",
                tree.path("connections").get(1).path("credentialRef").asText());
        Files.write(config, LocalConnectionRepository.JSON.writeValueAsBytes(tree));
        assertReason(() -> service.get(1), "LOCAL_STORE_UNAVAILABLE");
    }

    @Test void failedAtomicReplaceKeepsPreviousConfigurationAndCredentialUsable() throws Exception {
        var fail = new AtomicBoolean(false);
        var files = new LocalPrivateFiles(root, (source, target) -> {
            if (fail.get() && target.getFileName().toString().equals("connections.json")) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "fixture failure");
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        var isolated = service(new LocalConnectionRepository(files));
        var first = input("safe"); first.setPassword("original-secret"); var original = isolated.save(first, true);
        byte[] config = Files.readAllBytes(root.resolve("connections.json"));
        var change = update(original); change.setPassword("replacement-secret"); fail.set(true);
        assertReason(() -> isolated.save(change, false), "LOCAL_STORE_UNAVAILABLE");
        assertThat(Files.readAllBytes(root.resolve("connections.json"))).isEqualTo(config);
        assertThat(service(new LocalConnectionRepository(root)).getById(original.id()).getPassword()).isEqualTo("original-secret");
        // New encrypted object is left as an orphan; a failed/uncertain publication can never leave a dangling reference.
    }

    @Test void ownerOnlyPermissionsAndSymbolicLinkRejection() throws Exception {
        var input = input("private"); input.setPassword("fixture-secret"); service.save(input, true);
        if (Files.getFileAttributeView(root, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(root)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(root.resolve(".credentials/master.key"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        } else {
            var acl = Files.getFileAttributeView(root.resolve(".credentials/master.key"), java.nio.file.attribute.AclFileAttributeView.class);
            assertThat(acl).isNotNull(); assertThat(acl.getAcl()).hasSize(1);
        }
        // Symlink creation on Windows requires developer mode or a privileged test environment.
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            Path outside = Files.writeString(directory.resolve("outside.json"), "outside");
            Files.delete(root.resolve("connections.json")); Files.createSymbolicLink(root.resolve("connections.json"), outside);
            assertReason(() -> service.list(), "LOCAL_STORE_UNAVAILABLE");
            assertThat(Files.readString(outside)).isEqualTo("outside");
        }
    }

    @Test void separateRepositoryObjectsSerializeConcurrentEdits() throws Exception {
        var first = service.save(input("race"), true);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<Future<String>>();
            for (int index = 0; index < 2; index++) {
                final int n = index;
                tasks.add(pool.submit(() -> {
                    RequestContext.set(new LocalRuntimeIdentity().newRequestContext()); start.await();
                    try {
                        var input = update(first); input.setName("winner-" + n);
                        service(new LocalConnectionRepository(root)).save(input, false); return "OK";
                    } catch (LocalRequestException e) { return e.response().reasonCode(); }
                    finally { RequestContext.clear(); }
                }));
            }
            start.countDown();
            assertThat(List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "CONNECTION_REVISION_CONFLICT");
            assertThat(service.get(first.id()).version()).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void separateJvmProcessesCannotLoseUpdatesOrAllocateDuplicateIds() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var children = new ArrayList<Process>();
        try {
            for (String prefix : List.of("left", "right")) {
                children.add(new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        LocalConnectionWriterProcess.class.getName(), root.toString(), driver.toString(), prefix)
                        .redirectErrorStream(true).redirectOutput(directory.resolve(prefix + ".log").toFile()).start());
            }
            for (Process child : children) {
                assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(child.exitValue()).isZero();
            }
            var items = service.list();
            assertThat(items).hasSize(12); assertThat(items.stream().map(LocalConnectionRecord.PublicView::id).distinct()).hasSize(12);
            repository.locked(state -> { assertThat(state.revision()).isEqualTo(12); assertThat(state.nextConnectionId()).isEqualTo(13); return null; });
        } finally { children.forEach(Process::destroyForcibly); }
    }

    @Test void corruptedOversizedFileIsNotReadAsAnEmptyStore() throws Exception {
        Files.createDirectories(root); Path path = root.resolve("connections.json");
        try (var output = Files.newOutputStream(path)) { output.write(new byte[LocalConnectionRepository.MAX_BYTES + 1]); }
        assertReason(() -> service.list(), "LOCAL_STORE_UNAVAILABLE");
        assertThat(Files.size(path)).isEqualTo(LocalConnectionRepository.MAX_BYTES + 1);
    }

    @Test void internalAccessPortRequiresTrustedPersonalContext() {
        var first = service.save(input("scope"), true);
        RequestContext.clear(); assertReason(() -> service.getById(first.id()), "LOCAL_SCOPE_FORBIDDEN");
        RequestContext.set(new LocalRuntimeIdentity().newRequestContext()); RequestContext.get().setOrgId(42L);
        assertReason(() -> service.getById(first.id()), "LOCAL_SCOPE_FORBIDDEN");
    }

    @Test void exhaustedCountersRefuseWritesWithoutChangingExistingFile() throws Exception {
        Files.createDirectories(root);
        Path path = root.resolve("connections.json");
        for (var state : List.of(new LocalConnectionRepository.State(1, LocalConnectionRepository.MAX_NUMBER, 1, List.of()),
                new LocalConnectionRepository.State(1, 0, LocalConnectionRepository.MAX_NUMBER, List.of()))) {
            byte[] before = LocalConnectionRepository.JSON.writeValueAsBytes(state); Files.write(path, before);
            assertReason(() -> service.save(input("exhausted"), true), "LOCAL_STORE_UNAVAILABLE");
            assertThat(Files.readAllBytes(path)).isEqualTo(before);
        }
    }

    @Test void incorrectKeyFailsWithoutOverwritingIt() throws Exception {
        var input = input("key-replaced"); input.setPassword("fixture-secret"); service.save(input, true);
        Path key = root.resolve(".credentials/master.key");
        byte[] wrong = new byte[32]; new java.security.SecureRandom().nextBytes(wrong); Files.write(key, wrong);
        assertReason(() -> service.get(1), "LOCAL_STORE_UNAVAILABLE");
        assertThat(Files.readAllBytes(key)).isEqualTo(wrong);
    }
}
