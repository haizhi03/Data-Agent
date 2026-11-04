package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.config.ai.QwenProperties;
import java.util.function.Function;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

/** Deployment references and workspace-scoped encrypted credentials. */
@Component
public class CredentialService {
    private final ConfigurableEnvironment environment;
    private final QwenProperties qwen;
    private final org.springframework.beans.factory.ObjectProvider<edu.zsc.ai.domain.service.ai.model.FileCredentialStore> stored;
    public CredentialService(ConfigurableEnvironment environment, QwenProperties qwen) { this(environment, qwen, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public CredentialService(ConfigurableEnvironment environment, QwenProperties qwen,
            org.springframework.beans.factory.ObjectProvider<edu.zsc.ai.domain.service.ai.model.FileCredentialStore> stored) {
        this.environment = environment; this.qwen = qwen; this.stored = stored;
    }
    public <T> T withSecret(ResolvedModelConfig config, Function<String, T> action) {
        String ref = config.credentialRef();
        if (ref != null && ref.startsWith("credential:")) {
            var store = stored == null ? null : stored.getIfAvailable();
            if (store == null) throw ModelIntegrationException.unavailable();
            return store.withSecret(config, action, this::deploymentSecret);
        }
        String secret = deploymentSecret(ref);
        if (secret == null || secret.isBlank() || secret.equals("******")) throw ModelIntegrationException.unavailable();
        return action.apply(secret);
    }
    private String deploymentSecret(String ref) {
        String secret;
        if ("deployment:qwen".equals(ref)) secret = qwen.getApiKey();
        else if (ref != null && ref.matches("env:[A-Z][A-Z0-9_]*")) {
            // systemEnvironment only: not arbitrary Spring configuration/property traversal.
            secret = environment.getSystemEnvironment().get(ref.substring(4)) instanceof String value ? value : null;
        } else throw ModelIntegrationException.unavailable();
        if (secret == null || secret.isBlank() || secret.equals("******")) throw ModelIntegrationException.unavailable();
        return secret;
    }
}
