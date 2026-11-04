package edu.zsc.ai.domain.model.dto.request.ai;

import edu.zsc.ai.config.ai.model.ModelApiStyle;
import edu.zsc.ai.config.ai.model.ModelPurpose;
import edu.zsc.ai.config.ai.model.MultiProviderProperties.Options;
import edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode;
import java.util.Map;

public final class ModelManagementRequests {
    private ModelManagementRequests() {}
    public record Connection(Long expectedVersion, String code, String name, String provider, ModelApiStyle apiStyle,
                             String endpoint, String compatibilityProfile, String apiKey, boolean enabled) {
        @Override public String toString() { return "ModelConnectionRequest[redacted]"; }
    }
    public record Credential(Long expectedVersion, String action, String apiKey) {
        @Override public String toString() { return "ModelCredentialRequest[redacted]"; }
    }
    public record Model(Long expectedVersion, String code, String connectionId, String name, String modelId,
                        String capabilityProfile, Options defaultOptions, int maxContextTokens, int memoryThreshold,
                        boolean chatVisible, boolean enabled) {}
    public record Version(Long expectedVersion) {}
    public record Binding(Long expectedVersion, String modelConfigId, ThinkingMode thinkingMode) {
        public Binding(Long expectedVersion,String modelConfigId){this(expectedVersion,modelConfigId,null);}
    }
    public record PresetBinding(Long expectedVersion, String connectionId, Long connectionExpectedVersion, String modelId, ThinkingMode thinkingMode) {
        public PresetBinding(Long expectedVersion,String connectionId,Long connectionExpectedVersion,String modelId){this(expectedVersion,connectionId,connectionExpectedVersion,modelId,null);}
    }
    public record Test(String level) {}
    public record Setup(String modelConfigId, Long modelExpectedVersion, String connectionId, Long connectionExpectedVersion,
                        String name, String provider, ModelApiStyle apiStyle, String endpoint, String compatibilityProfile,
                        String apiKey, String modelId, String capabilityProfile, Options defaultOptions,
                        Integer maxContextTokens, Integer memoryThreshold, Boolean chatVisible, Boolean enabled,
                        boolean makeDefault, Map<ModelPurpose,Long> bindingVersions) {
        @Override public String toString() { return "ModelSetupRequest[redacted]"; }
    }
    public record ConnectionTest(String connectionId, Long expectedVersion, String provider, ModelApiStyle apiStyle,
                                 String endpoint, String compatibilityProfile, String modelId, String apiKey) {
        @Override public String toString() { return "ModelConnectionTestRequest[redacted]"; }
    }
}
