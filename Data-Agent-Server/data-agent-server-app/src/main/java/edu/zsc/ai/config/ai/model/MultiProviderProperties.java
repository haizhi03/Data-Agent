package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned M1 repository. No HTTP endpoint accepts these properties. */
@Data
@ConfigurationProperties(prefix = "ai")
public class MultiProviderProperties {
    private Routing modelRouting = new Routing();
    private List<Connection> modelConnections = new ArrayList<>();
    private List<Model> modelConfigs = new ArrayList<>();
    private List<Bindings> modelBindings = new ArrayList<>();

    @Data
    public static class Routing {
        private boolean enabled;
        /** Enables workspace JSON files. Startup-only mode remains available for rollback. */
        private boolean persistenceEnabled;
        private String storageDirectory = java.nio.file.Path.of(System.getProperty("user.home"),".data-agent","model-config").toString();
        private boolean legacyAliasesEnabled = true;
        /** Deployment approval only; management API cannot change this list. */
        private List<String> allowedPrivateHosts = new ArrayList<>();
        private int maxCallsPerRun = 64;
        /** Explicit scopes authorized to use the existing deployment Qwen key. Empty means no import. */
        private List<Bindings> legacyWorkspaces = new ArrayList<>();
    }

    @Data
    public static class Connection {
        private String code;
        private WorkspaceTypeEnum workspaceType;
        private Long ownerId;
        private String provider;
        private ModelApiStyle apiStyle;
        private String endpoint;
        private String authType = "API_KEY";
        private String credentialRef;
        private long credentialVersion = 1;
        private String compatibilityProfile = "openai-chat-v1";
        private long version = 1;
        private boolean enabled = true;
        /** Explicit administrator approval of private DNS targets, exact hosts only. */
        private List<String> allowedPrivateHosts = new ArrayList<>();
    }

    @Data
    public static class Model {
        private String code;
        private String connectionCode;
        private WorkspaceTypeEnum workspaceType;
        private Long ownerId;
        private String name;
        private String modelId;
        private String capabilityProfile = "text-tools-v1";
        private Options defaultOptions = new Options();
        private List<String> legacyNames = new ArrayList<>();
        private boolean chatVisible = true;
        private int maxContextTokens = 32768;
        private int memoryThreshold = 24000;
        private long version = 1;
        private boolean enabled = true;
    }

    @Data
    public static class Options {
        private ThinkingMode thinkingMode = ThinkingMode.DISABLED;
        private Integer thinkingBudget;
        private Double temperature;
        private Integer maxOutputTokens;
        private String reasoningEffort;
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknownOption(String name, Object value) {
            throw new IllegalArgumentException("Unsupported model option");
        }
    }

    public enum ThinkingMode { DISABLED, ENABLED, ADAPTIVE }

    @Data
    public static class Bindings {
        private WorkspaceTypeEnum workspaceType;
        private Long ownerId;
        private Map<ModelPurpose, String> purposes = new EnumMap<>(ModelPurpose.class);
    }
}
