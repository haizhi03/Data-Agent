package edu.zsc.ai.domain.model.dto.response.ai;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Option item for model selection (exposed to frontend).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelOptionResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Legacy selector field: model name on the old route, scoped configuration ID on the new route.
     */
    private String modelName;
    private String modelConfigId;
    /** Upstream model ID for display, separate from the authorized selection ID. */
    private String modelId;
    private String name;
    private String provider;
    private String apiStyle;
    private String compatibilityProfile;
    private boolean defaultModel;

    /**
     * Whether this model supports thinking/reasoning mode (e.g. for displaying brain icon).
     */
    private boolean supportThinking;
    private edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode thinkingMode;
    @Builder.Default
    private java.util.List<edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode> thinkingModes=java.util.List.of();

    /**
     * Token threshold used by server-side memory compression heuristics.
     */
    private int memoryThreshold;

    /**
     * Maximum context window supported by the model.
     */
    private int maxContextTokens;
}
