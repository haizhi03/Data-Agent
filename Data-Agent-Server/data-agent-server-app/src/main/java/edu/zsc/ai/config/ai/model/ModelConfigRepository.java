package edu.zsc.ai.config.ai.model;

import java.util.List;

/** Runtime catalogue; implementations must never return secret material. */
public interface ModelConfigRepository {
    boolean enabled();
    int maxCallsPerRun();
    List<ResolvedModelConfig> list(ModelAccessContext access);
    ResolvedModelConfig find(String id);
    default ResolvedModelConfig find(String id, ModelPurpose purpose) { return find(id); }
    String binding(ModelAccessContext access, ModelPurpose purpose);
    String alias(ModelAccessContext access, String name);
    default <T> T inSnapshot(ModelAccessContext access, java.util.function.Supplier<T> action) { return action.get(); }
    default boolean permitsSnapshot(ResolvedModelConfig snapshot) {
        var current = find(snapshot.modelConfigId());
        return current != null && StartupModelConfigRepository.sameScope(current.access(), snapshot.access())
                && current.connectionId().equals(snapshot.connectionId()) && current.credentialRef().equals(snapshot.credentialRef())
                && current.credentialVersion() == snapshot.credentialVersion();
    }
}
