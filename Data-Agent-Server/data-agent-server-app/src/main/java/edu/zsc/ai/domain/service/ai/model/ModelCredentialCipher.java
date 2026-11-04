package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.ModelAccessContext;
import edu.zsc.ai.config.ai.model.MultiProviderProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

/** AES-256-GCM. Keys come from the deployment environment or an automatically initialized local file. */
@Component
public class ModelCredentialCipher {
    private final ConfigurableEnvironment environment;
    private final ModelCredentialKeyStore keys;
    private final SecureRandom random = new SecureRandom();
    public ModelCredentialCipher(ConfigurableEnvironment environment, MultiProviderProperties properties) {
        this.environment = environment;
        this.keys = new ModelCredentialKeyStore(environment, properties);
    }
    public String activeVersion() { return environment.getProperty("ai.model-credentials.active-key-version", "V1"); }
    public boolean configured() {
        try { byte[] key = keys.resolve(activeVersion(), true); Arrays.fill(key, (byte) 0); return true; }
        catch (ModelManagementException e) { return false; }
    }
    public String encrypt(String secret, ModelAccessContext scope, long id, String version) {
        return encrypt(secret,scope,Long.toString(id),version);
    }
    public String encrypt(String secret, ModelAccessContext scope, String id, String version) {
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext, nonce, scope, id, version);
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length); System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(payload);
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }
    public String decrypt(String payload, ModelAccessContext scope, long id, String version) {
        return decrypt(payload,scope,Long.toString(id),version);
    }
    public String decrypt(String payload, ModelAccessContext scope, String id, String version) {
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length < 29) throw ModelManagementException.unavailable();
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(raw, 12, raw.length), Arrays.copyOf(raw, 12), scope, id, version);
            try { return new String(plaintext, StandardCharsets.UTF_8); } finally { Arrays.fill(plaintext, (byte) 0); }
        } catch (RuntimeException e) { throw ModelManagementException.unavailable(); }
    }
    private byte[] crypt(int mode, byte[] input, byte[] nonce, ModelAccessContext scope, String id, String version) {
        byte[] key = keys.resolve(version, mode == Cipher.ENCRYPT_MODE);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD((scope.workspaceType() + "/" + scope.ownerId() + "/" + id + "/" + version).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (Exception e) { throw ModelManagementException.unavailable(); }
        finally { Arrays.fill(key, (byte) 0); }
    }
}
