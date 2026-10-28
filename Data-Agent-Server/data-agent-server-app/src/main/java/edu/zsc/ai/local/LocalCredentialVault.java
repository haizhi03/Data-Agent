package edu.zsc.ai.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Called only inside the repository lock. Independent encrypted objects allow publish-reference-last commits. */
final class LocalCredentialVault {
    private static final String PREFIX = "local-credential:";
    private final LocalPrivateFiles files;
    private final Path directory;
    private final SecureRandom random = new SecureRandom();

    LocalCredentialVault(LocalPrivateFiles files) { this.files = files; directory = files.root.resolve(".credentials"); }

    static void validateRef(String ref) {
        if (ref != null && (!ref.startsWith(PREFIX) || !UUID.fromString(ref.substring(PREFIX.length())).toString()
                .equals(ref.substring(PREFIX.length())))) throw LocalRequestException.storeUnavailable();
    }

    String create(long connectionId, Secret secret) {
        String ref = PREFIX + UUID.randomUUID();
        byte[] plaintext = null;
        byte[] key = null;
        try {
            files.directory(directory);
            key = key(true);
            plaintext = LocalConnectionRepository.JSON.writeValueAsBytes(secret);
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, connectionId, ref);
            byte[] encrypted = cipher.doFinal(plaintext);
            var payload = new Encrypted(1, connectionId, Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(encrypted));
            byte[] encoded = LocalConnectionRepository.JSON.writeValueAsBytes(payload);
            if (encoded.length > 2_097_152) throw LocalRequestException.storeUnavailable();
            files.atomicWrite(path(ref), encoded);
            return ref;
        } catch (Exception e) { throw LocalRequestException.storeUnavailable(); }
        finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); if (key != null) Arrays.fill(key, (byte) 0); }
    }

    Secret read(long connectionId, String ref) {
        if (ref == null) return new Secret(null, Map.of());
        byte[] plaintext = null;
        byte[] key = null;
        try {
            validateRef(ref);
            files.directory(directory);
            var payload = LocalConnectionRepository.JSON.readValue(files.read(path(ref), 2_097_152), Encrypted.class);
            if (payload.formatVersion != 1 || payload.connectionId != connectionId) throw LocalRequestException.storeUnavailable();
            key = key(false);
            byte[] nonce = Base64.getDecoder().decode(payload.nonce);
            if (nonce.length != 12) throw LocalRequestException.storeUnavailable();
            plaintext = cipher(Cipher.DECRYPT_MODE, key, nonce, connectionId, ref)
                    .doFinal(Base64.getDecoder().decode(payload.payload));
            Secret secret = LocalConnectionRepository.JSON.readValue(plaintext, Secret.class);
            LocalConnectionValidation.validateProperties(secret.properties);
            if (secret.properties.keySet().stream().anyMatch(LocalConnectionValidation::publicProperty)) throw LocalRequestException.storeUnavailable();
            return secret;
        } catch (Exception e) { throw LocalRequestException.storeUnavailable(); }
        finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); if (key != null) Arrays.fill(key, (byte) 0); }
    }

    private byte[] key(boolean create) throws IOException {
        Path key = directory.resolve("master.key"), marker = directory.resolve("initialized");
        LocalPrivateFiles.rejectLink(key); LocalPrivateFiles.rejectLink(marker);
        if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
            byte[] value = files.read(key, 32);
            if (value.length != 32) { Arrays.fill(value, (byte) 0); throw LocalRequestException.storeUnavailable(); }
            if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) files.atomicWrite(marker, new byte[]{1});
            return value;
        }
        try (var existing = Files.newDirectoryStream(directory, "*.json")) {
            if (!create || Files.exists(marker, LinkOption.NOFOLLOW_LINKS) || existing.iterator().hasNext()) {
                throw LocalRequestException.storeUnavailable();
            }
        }
        byte[] value = new byte[32]; random.nextBytes(value);
        try { files.atomicWrite(key, value); files.atomicWrite(marker, new byte[]{1}); return value; }
        catch (IOException | RuntimeException e) { Arrays.fill(value, (byte) 0); throw e; }
    }

    private Cipher cipher(int mode, byte[] key, byte[] nonce, long connectionId, String ref) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("data-agent/local-connection/v1/" + connectionId + "/" + ref).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private Path path(String ref) { validateRef(ref); return directory.resolve(ref.substring(PREFIX.length()) + ".json"); }

    void removeUnreferenced(String ref) {
        if (ref == null) return;
        // An unsuccessful cleanup leaves an orphan; it cannot invalidate a committed reference.
        try { var path = path(ref); LocalPrivateFiles.rejectLink(path); Files.deleteIfExists(path); }
        catch (IOException | RuntimeException ignored) { }
    }

    record Secret(String password, Map<String, String> properties) {
        Secret { properties = Map.copyOf(properties); }
        boolean empty() { return (password == null || password.isEmpty()) && properties.isEmpty(); }
        @Override public String toString() { return "Secret[redacted]"; }
    }
    private record Encrypted(int formatVersion, long connectionId, String nonce, String payload) { }
}
