package edu.zsc.ai.local;

import edu.zsc.ai.config.ai.QwenProperties;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.service.ai.model.*;
import edu.zsc.ai.api.controller.ai.ModelManagementController;
import org.springframework.context.annotation.*;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reuse the formal model document, credential cipher and protocol adapters, with a dedicated local root. */
@Configuration(proxyBeanMethods = false)
@Import({FileModelStore.class, ModelCredentialCipher.class, ModelManagementService.class,
        ModelDiagnosticService.class, ModelConnectionTestService.class, ModelConnectionProbe.class,
        ModelClientRegistry.class, ModelRuntime.class, OpenAiChatClientFactory.class, ResponsesClientFactory.class,
        AnthropicMessagesClientFactory.class, DashScopeClientFactory.class, ModelManagementController.class,
        LocalWriteService.class, LocalWriteExecutor.class, LocalConversationRegistry.class, LocalAssistantService.class, LocalAssistantController.class})
class LocalAssistantConfiguration {
    @Bean edu.zsc.ai.domain.service.agent.ChatReferenceService localChatReferences(LocalConnectionService connections) {
        return new edu.zsc.ai.domain.service.agent.ChatReferenceService(connections, connections);
    }
    @Bean edu.zsc.ai.domain.service.agent.runtimecontext.strategy.ExplicitReferencesStrategy localExplicitReferences() {
        return new edu.zsc.ai.domain.service.agent.runtimecontext.strategy.ExplicitReferencesStrategy();
    }
    @Bean FileCredentialStore localFileCredentials(FileModelStore store, ModelCredentialCipher cipher) {
        return new FileCredentialStore(store,cipher);
    }
    @Bean MultiProviderProperties localModelProperties() { return new MultiProviderProperties(); }
    @Bean static org.springframework.beans.factory.config.BeanPostProcessor localModelIsolation(ConfigurableEnvironment environment) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof MultiProviderProperties properties) {
                    // Run after @ConfigurationProperties binding: server ai.* defaults must never overwrite the local root.
                    var root=java.nio.file.Path.of(environment.getProperty("app.local.data-directory",
                            java.nio.file.Path.of(System.getProperty("user.home"),".data-agent","local").toString()));
                    var routing=new MultiProviderProperties.Routing();
                    routing.setEnabled(true); routing.setPersistenceEnabled(true); routing.setLegacyAliasesEnabled(false);
                    routing.setStorageDirectory(root.resolve("models").toString()); routing.setMaxCallsPerRun(16);
                    routing.setAllowedPrivateHosts(org.springframework.boot.context.properties.bind.Binder.get(environment)
                            .bind("app.local.model-allowed-private-hosts",org.springframework.boot.context.properties.bind.Bindable.listOf(String.class)).orElse(java.util.List.of()));
                    properties.setModelRouting(routing); properties.setModelConnections(java.util.List.of());
                    properties.setModelConfigs(java.util.List.of()); properties.setModelBindings(java.util.List.of());
                }
                return bean;
            }
        };
    }
    @Bean ModelConfigRepository localModelRepository(FileModelStore store, MultiProviderProperties properties) {
        return new FileModelConfigRepository(store, properties);
    }
    @Bean ModelManagementAccess localModelAccess(LocalConnectionService connections) {
        return new ModelManagementAccess() {
            @Override public ModelAccessContext current(boolean manage) {
                connections.assertWorkbenchApiAllowed(); return super.current(manage);
            }
        };
    }
    @Bean ModelConfigResolver localModelResolver(ModelConfigRepository repository) {
        return new ModelConfigResolver(repository);
    }
    @Bean CredentialService localModelCredentials(ConfigurableEnvironment environment,
            org.springframework.beans.factory.ObjectProvider<FileCredentialStore> stored) {
        return new CredentialService(environment, new QwenProperties(), stored);
    }
}
