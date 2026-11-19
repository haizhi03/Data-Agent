package edu.zsc.ai.config.ai.model;

public interface ModelClientFactory {
    boolean supports(ModelApiStyle apiStyle);
    ModelClientBundle create(ResolvedModelConfig config);
    /** Draft probes use the same adapter with an invocation-local credential source. */
    default ModelClientBundle create(ResolvedModelConfig config, CredentialService credentials) {
        throw ModelIntegrationException.capability();
    }
    static ModelClientFactory select(java.util.List<ModelClientFactory> factories, ModelApiStyle style) {
        var matching = factories.stream().filter(factory -> factory.supports(style)).toList();
        if (matching.size() != 1) throw ModelIntegrationException.capability();
        return matching.get(0);
    }
}
