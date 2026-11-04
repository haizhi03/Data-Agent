package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.domain.service.org.OrgAccessService;
import edu.zsc.ai.domain.service.ai.model.ModelThinkingOptions;
import java.util.EnumMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static edu.zsc.ai.config.ai.model.StartupModelConfigRepository.sameScope;

@Component
@RequiredArgsConstructor
public class ModelConfigResolver {
    private final ModelConfigRepository repository;
    private final OrgAccessService orgAccess;

    public boolean enabled() { return repository.enabled(); }
    public ModelRunSnapshot resolveRun(String modelConfigId, String legacyName) {
        return resolveRun(modelConfigId,legacyName,null);
    }
    public ModelRunSnapshot resolveRun(String modelConfigId,String legacyName,MultiProviderProperties.ThinkingMode thinkingMode) {
        ModelAccessContext access = ModelAccessContext.current();
        authorize(access);
        return repository.inSnapshot(access, () -> resolveRun(access,modelConfigId,legacyName,thinkingMode));
    }
    private ModelRunSnapshot resolveRun(ModelAccessContext access,String modelConfigId,String legacyName,MultiProviderProperties.ThinkingMode thinkingMode) {
        String alias = blank(legacyName) ? null : repository.alias(access, legacyName.trim());
        // Older clients echo modelName from /models. In routed mode it is a scoped configuration ID.
        if (alias == null && !blank(legacyName) && repository.find(legacyName) != null) alias = legacyName;
        if (!blank(legacyName) && alias == null) throw ModelIntegrationException.unavailable();
        if (!blank(modelConfigId) && alias != null && !modelConfigId.equals(alias)) throw ModelIntegrationException.unavailable();
        String mainId = !blank(modelConfigId) ? modelConfigId : alias != null ? alias : repository.binding(access, ModelPurpose.MAIN_CHAT);
        EnumMap<ModelPurpose, ResolvedModelConfig> models = new EnumMap<>(ModelPurpose.class);
        for (ModelPurpose purpose : ModelPurpose.values()) {
            String id = purpose == ModelPurpose.MAIN_CHAT ? mainId : repository.binding(access, purpose);
            ResolvedModelConfig config = resolve(id, access, purpose);
            if (purpose == ModelPurpose.MAIN_CHAT && !config.chatVisible()) throw ModelIntegrationException.unavailable();
            if (purpose == ModelPurpose.MAIN_CHAT) config=ModelThinkingOptions.forRequest(config,thinkingMode);
            models.put(purpose, config);
        }
        return new ModelRunSnapshot(models, repository.maxCallsPerRun());
    }

    public List<ResolvedModelConfig> listAvailable() {
        ModelAccessContext access = ModelAccessContext.current();
        authorize(access);
        return repository.list(access).stream().filter(ResolvedModelConfig::chatVisible).toList();
    }
    public boolean isDefault(ResolvedModelConfig config) {
        return config.modelConfigId().equals(repository.binding(ModelAccessContext.current(), ModelPurpose.MAIN_CHAT));
    }
    public void reauthorize(ResolvedModelConfig snapshot) {
        authorize(snapshot.access());
        if (!repository.permitsSnapshot(snapshot)) throw ModelIntegrationException.unavailable();
    }
    private ResolvedModelConfig resolve(String id, ModelAccessContext access, ModelPurpose purpose) {
        ResolvedModelConfig c = id == null ? null : repository.find(id, purpose);
        if (c == null || !sameScope(c.access(), access)) throw ModelIntegrationException.unavailable();
        return new ResolvedModelConfig(c.modelConfigId(), c.configVersion(), c.connectionId(), c.connectionVersion(),
                access, c.provider(), c.apiStyle(), c.modelId(), c.name(), c.endpoint(), c.credentialRef(), c.credentialVersion(),
                c.capabilityProfile(), c.compatibilityProfile(), c.options(), c.maxContextTokens(), c.memoryThreshold(), c.chatVisible(), c.allowedPrivateHosts());
    }
    private void authorize(ModelAccessContext access) {
        if (!enabled() || access.userId() <= 0) throw ModelIntegrationException.unavailable();
        if (access.workspaceType() == WorkspaceTypeEnum.ORGANIZATION) {
            try { orgAccess.loadActiveMembership(access.userId(), access.ownerId()); }
            catch (RuntimeException e) { throw ModelIntegrationException.unavailable(); }
        } else if (access.userId() != access.ownerId()) throw ModelIntegrationException.unavailable();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
