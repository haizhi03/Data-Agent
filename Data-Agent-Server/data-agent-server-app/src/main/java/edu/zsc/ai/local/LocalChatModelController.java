package edu.zsc.ai.local;

import edu.zsc.ai.config.ai.model.ModelConfigResolver;
import edu.zsc.ai.config.ai.model.ProviderCompatibilityPolicy;
import edu.zsc.ai.domain.model.dto.response.ai.ModelOptionResponse;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.service.ai.model.ModelThinkingOptions;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Chat choices from the local model repository, without importing the legacy application graph. */
@RestController
final class LocalChatModelController {
    private final ModelConfigResolver resolver;

    LocalChatModelController(ModelConfigResolver resolver) { this.resolver = resolver; }

    @GetMapping("/api/ai/models")
    ApiResponse<List<ModelOptionResponse>> models() {
        return ApiResponse.success(resolver.listAvailable().stream().map(model -> ModelOptionResponse.builder()
                .modelName(model.modelConfigId()).modelConfigId(model.modelConfigId())
                .modelId(model.modelId()).name(model.name()).provider(model.provider())
                .apiStyle(model.apiStyle().name()).compatibilityProfile(model.compatibilityProfile())
                .defaultModel(resolver.isDefault(model))
                .supportThinking(ProviderCompatibilityPolicy.supportsThinking(model.capabilityProfile()))
                .thinkingMode(model.options().thinkingMode())
                .thinkingModes(ModelThinkingOptions.modes(model.capabilityProfile(), model.apiStyle(), model.compatibilityProfile()))
                .memoryThreshold(model.memoryThreshold()).maxContextTokens(model.maxContextTokens())
                .build()).toList());
    }
}
