package edu.zsc.ai.api.controller.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import edu.zsc.ai.config.ai.AiModelCatalog;
import edu.zsc.ai.config.ai.AiModelProperties;
import edu.zsc.ai.config.ai.model.ModelApiStyle;
import edu.zsc.ai.config.ai.model.ModelConfigResolver;
import edu.zsc.ai.config.ai.model.ResolvedModelConfig;
import edu.zsc.ai.domain.model.dto.response.ai.ModelOptionResponse;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;

class AiConfigControllerTest {

    private final AiModelProperties modelProperties = new AiModelProperties();
    private final AiModelCatalog aiModelCatalog = new AiModelCatalog(modelProperties);
    private final ModelConfigResolver resolver = mock(ModelConfigResolver.class);
    private final AiConfigController controller = new AiConfigController(aiModelCatalog, resolver);

    AiConfigControllerTest() {
        aiModelCatalog.initialize();
    }

    @Test
    void listModels_returnsConfiguredModelMetadata() {
        ApiResponse<List<ModelOptionResponse>> response = controller.listModels();

        assertNotNull(response);
        assertNotNull(response.getData());

        Map<String, ModelOptionResponse> responsesByModelName = response.getData().stream()
                .collect(Collectors.toMap(ModelOptionResponse::getModelName, Function.identity()));

        assertEquals(List.of("qwen3.6-max-preview", "qwen3-max-2026-01-23"),
                response.getData().stream().map(ModelOptionResponse::getModelName).toList());
        assertEquals(aiModelCatalog.listChatVisibleModels().size(), responsesByModelName.size());
        assertFalse(responsesByModelName.containsKey("qwen3.6-plus"));
        for (AiModelProperties.ModelDefinition model : aiModelCatalog.listChatVisibleModels()) {
            ModelOptionResponse option = responsesByModelName.get(model.getModelName());
            assertNotNull(option);
            assertEquals(model.getModelName(), option.getModelId());
            assertEquals("QWEN", option.getProvider());
            assertEquals(model.isSupportThinking(), option.isSupportThinking());
            assertEquals(model.getMemoryThreshold(), option.getMemoryThreshold());
            assertEquals(model.getMaxContextTokens(), option.getMaxContextTokens());
        }
    }

    @Test
    void routedModelsKeepAuthorizedSelectionIdsSeparateFromDisplayMetadata() {
        when(resolver.enabled()).thenReturn(true);
        var primary = routedModel("scoped-primary", "主连接");
        var backup = routedModel("scoped-backup", "备用连接");
        when(resolver.listAvailable()).thenReturn(List.of(primary, backup));
        when(resolver.isDefault(primary)).thenReturn(true);

        var options = controller.listModels().getData();
        assertEquals(List.of("scoped-primary", "scoped-backup"),
                options.stream().map(ModelOptionResponse::getModelName).toList());
        assertEquals(List.of("scoped-primary", "scoped-backup"),
                options.stream().map(ModelOptionResponse::getModelConfigId).toList());
        assertEquals(List.of("主连接", "备用连接"), options.stream().map(ModelOptionResponse::getName).toList());
        for (var option : options) {
            assertEquals("qwen3.8-max", option.getModelId());
            assertEquals("OPENAI", option.getProvider());
            assertEquals("qwen-chat-v1", option.getCompatibilityProfile());
            assertEquals("OPENAI_CHAT_COMPLETIONS", option.getApiStyle());
            assertTrue(option.isSupportThinking());
            assertEquals(edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.DISABLED,option.getThinkingMode());
            assertEquals(List.of(edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.DISABLED,edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.ENABLED),option.getThinkingModes());
        }
        assertTrue(options.get(0).isDefaultModel());
        assertFalse(options.get(1).isDefaultModel());
    }

    private ResolvedModelConfig routedModel(String id, String name) {
        var model = mock(ResolvedModelConfig.class);
        when(model.modelConfigId()).thenReturn(id);
        when(model.modelId()).thenReturn("qwen3.8-max");
        when(model.name()).thenReturn(name);
        when(model.provider()).thenReturn("OPENAI"); // Older Qwen connections can have this label.
        when(model.apiStyle()).thenReturn(ModelApiStyle.OPENAI_CHAT_COMPLETIONS);
        when(model.compatibilityProfile()).thenReturn("qwen-chat-v1");
        when(model.capabilityProfile()).thenReturn("qwen-tools-v1");
        when(model.options()).thenReturn(new ResolvedModelConfig.EffectiveOptions(edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.DISABLED,null,null,8192));
        return model;
    }
}
