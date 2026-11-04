package edu.zsc.ai.api.controller.ai;

import edu.zsc.ai.config.ai.AiModelCatalog;
import edu.zsc.ai.config.ai.model.ModelConfigResolver;
import edu.zsc.ai.domain.model.dto.response.ai.ModelOptionResponse;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI configuration endpoints (e.g. selectable models for chat).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai")
public class AiConfigController {

    private final AiModelCatalog aiModelCatalog;
    private final ModelConfigResolver modelConfigResolver;

    /**
     * Returns the list of models available for chat. Frontend uses this for model selector.
     */
    @GetMapping("/models")
    public ApiResponse<List<ModelOptionResponse>> listModels() {
        if (modelConfigResolver.enabled()) {
            return ApiResponse.success(modelConfigResolver.listAvailable().stream().map(c -> ModelOptionResponse.builder()
                    .modelName(c.modelConfigId()).modelConfigId(c.modelConfigId()).modelId(c.modelId()).name(c.name()).provider(c.provider())
                    .compatibilityProfile(c.compatibilityProfile())
                    .apiStyle(c.apiStyle().name()).defaultModel(modelConfigResolver.isDefault(c)).supportThinking(edu.zsc.ai.config.ai.model.ProviderCompatibilityPolicy.supportsThinking(c.capabilityProfile()))
                    .thinkingMode(c.options().thinkingMode()).thinkingModes(edu.zsc.ai.domain.service.ai.model.ModelThinkingOptions.modes(c.capabilityProfile(),c.apiStyle(),c.compatibilityProfile()))
                    .memoryThreshold(c.memoryThreshold()).maxContextTokens(c.maxContextTokens()).build()).toList());
        }
        List<ModelOptionResponse> list = aiModelCatalog.listChatVisibleModels().stream()
                .map(m -> ModelOptionResponse.builder()
                        .modelName(m.getModelName())
                        .modelId(m.getModelName()).provider("QWEN").compatibilityProfile("qwen-chat-v1")
                        .defaultModel(m.getModelName().equals(aiModelCatalog.defaultModelName()))
                        .supportThinking(m.isSupportThinking())
                        .memoryThreshold(m.getMemoryThreshold())
                        .maxContextTokens(m.getMaxContextTokens())
                        .build())
                .toList();
        return ApiResponse.success(list);
    }
}
