package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Opt-in private parent/child pipe. Never an HTTP credential endpoint or a plaintext log message. */
@Component
@ConditionalOnProperty(name = "app.local.host-stdio-enabled", havingValue = "true")
final class LocalHostBootstrap {
    private final LocalRuntimeIdentity identity;
    LocalHostBootstrap(LocalRuntimeIdentity identity) { this.identity = identity; }
    @EventListener public void started(WebServerInitializedEvent event) {
        byte[] key = null;
        try {
            String encoded = new String(System.in.readNBytes(45), StandardCharsets.US_ASCII).trim();
            if (!encoded.matches("[A-Za-z0-9_-]{43}")) throw new IllegalStateException();
            key = Base64.getUrlDecoder().decode(encoded);
            System.out.println("DATA_AGENT_LOCAL_BOOTSTRAP_V1 " + frame(key, identity, event.getWebServer().getPort()));
            System.out.flush();
        } catch (Exception e) { throw new IllegalStateException("Controlled local bootstrap failed"); }
        finally { if (key != null) Arrays.fill(key, (byte) 0); }
    }
    static String frame(byte[] key, LocalRuntimeIdentity identity, int port) throws Exception {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        byte[] plaintext = new ObjectMapper().writeValueAsBytes(Map.of("contractVersion", 1,
                "runtimeInstanceId", identity.runtimeInstanceId().toString(), "transportToken", identity.transportTokenForHost(),
                "apiBaseURL", "http://127.0.0.1:" + port + "/api"));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("data-agent-local-bootstrap-v1".getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(plaintext), payload = new byte[12 + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, 12); System.arraycopy(encrypted, 0, payload, 12, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }
}
