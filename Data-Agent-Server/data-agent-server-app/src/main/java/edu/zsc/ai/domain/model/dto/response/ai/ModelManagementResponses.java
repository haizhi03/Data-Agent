package edu.zsc.ai.domain.model.dto.response.ai;

import edu.zsc.ai.config.ai.model.*;
import java.util.List;

public final class ModelManagementResponses {
    private ModelManagementResponses() {}
    public record Status(boolean enabled, boolean persistent, boolean canManage, boolean credentialStorageReady) {}
    public record ConnectionTest(boolean success, String code, long elapsedMs) {}
    public record Setup(Connection connection, Model model, List<Binding> bindings) {}
    public record Connection(String id, String code, String name, String provider, ModelApiStyle apiStyle, String endpoint,
                             String requestUrl, String compatibilityProfile, String authType, boolean hasCredential,
                             String credentialKind, long credentialVersion, long version, boolean enabled) {}
    public record Model(String id, String code, String connectionId, String name, String modelId, String capabilityProfile,
                        MultiProviderProperties.Options defaultOptions, int maxContextTokens, int memoryThreshold,
                        boolean chatVisible, long version, boolean enabled, boolean available, ConfiguredTokenLimits tokenLimits, boolean taskPreset) {
        public Model(String id,String code,String connectionId,String name,String modelId,String capabilityProfile,
                     MultiProviderProperties.Options defaultOptions,int maxContextTokens,int memoryThreshold,
                     boolean chatVisible,long version,boolean enabled,boolean available) {
            this(id,code,connectionId,name,modelId,capabilityProfile,defaultOptions,maxContextTokens,memoryThreshold,chatVisible,version,enabled,available,null,false);
        }
    }
    public record Binding(ModelPurpose purpose, String modelConfigId, long version,
                          MultiProviderProperties.ThinkingMode thinkingMode, List<MultiProviderProperties.ThinkingMode> thinkingModes) {
        public Binding(ModelPurpose purpose,String modelConfigId,long version){this(purpose,modelConfigId,version,null,List.of());}
    }
    public record Profile(ModelApiStyle apiStyle, String compatibilityProfile, String capabilityProfile,
                          List<MultiProviderProperties.ThinkingMode> thinkingModes, String resource) {}
    public record Test(String id, String modelConfigId, long configVersion, long connectionVersion, long credentialVersion,
                       String capabilityProfile, String compatibilityProfile, String sdkVersion, String requestedLevel,
                       String status, String result, String createdAt, String finishedAt, boolean current) {}
}
