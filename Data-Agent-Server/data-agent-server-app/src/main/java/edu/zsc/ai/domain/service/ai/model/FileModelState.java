package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.model.*;
import java.util.*;

/** One server-only JSON document per workspace. Never returned by a controller. */
public final class FileModelState {
    public int formatVersion = 1;
    public long revision;
    public WorkspaceTypeEnum workspaceType;
    public long ownerId;
    public boolean startupImported;
    public Map<String, Credential> credentials = new LinkedHashMap<>();
    public Map<String, Connection> connections = new LinkedHashMap<>();
    public Map<String, Model> models = new LinkedHashMap<>();
    public Map<ModelPurpose, Binding> bindings = new EnumMap<>(ModelPurpose.class);
    public Map<String, String> aliases = new LinkedHashMap<>();
    public record Credential(String id, String kind, String encryptedPayload, String secretRef, String keyVersion, long version) {
        @Override public String toString() { return "FileCredential[redacted]"; }
    }
    public record Connection(String id, String code, String name, String provider, ModelApiStyle apiStyle, String endpoint,
                             String compatibilityProfile, String credentialId, long credentialVersion, long version,
                             boolean enabled, Set<String> allowedPrivateHosts) {}
    public record Model(String id, String code, String connectionId, String name, String modelId, String capabilityProfile,
                        ResolvedModelConfig.EffectiveOptions options, int maxContextTokens, int memoryThreshold,
                        boolean chatVisible, long version, boolean enabled, ConfiguredTokenLimits tokenLimits, boolean taskPreset) {
        public Model(String id, String code, String connectionId, String name, String modelId, String capabilityProfile,
                     ResolvedModelConfig.EffectiveOptions options, int maxContextTokens, int memoryThreshold,
                     boolean chatVisible, long version, boolean enabled) {
            this(id,code,connectionId,name,modelId,capabilityProfile,options,maxContextTokens,memoryThreshold,chatVisible,version,enabled,null,false);
        }
        public Model(String id, String code, String connectionId, String name, String modelId, String capabilityProfile,
                     ResolvedModelConfig.EffectiveOptions options, int maxContextTokens, int memoryThreshold,
                     boolean chatVisible, long version, boolean enabled, ConfiguredTokenLimits tokenLimits) {
            this(id,code,connectionId,name,modelId,capabilityProfile,options,maxContextTokens,memoryThreshold,chatVisible,version,enabled,tokenLimits,false);
        }
        public Model withTokenLimits(ConfiguredTokenLimits limits) {
            return new Model(id,code,connectionId,name,modelId,capabilityProfile,options,maxContextTokens,memoryThreshold,chatVisible,version,enabled,limits,taskPreset);
        }
        public Model withTaskPreset(boolean preset) {
            return new Model(id,code,connectionId,name,modelId,capabilityProfile,options,maxContextTokens,memoryThreshold,chatVisible,version,enabled,tokenLimits,preset);
        }
    }
    public record Binding(String modelConfigId, long version, MultiProviderProperties.ThinkingMode thinkingMode) {
        public Binding(String modelConfigId,long version){this(modelConfigId,version,null);}
    }
    @Override public String toString() { return "FileModelState[redacted]"; }
}
