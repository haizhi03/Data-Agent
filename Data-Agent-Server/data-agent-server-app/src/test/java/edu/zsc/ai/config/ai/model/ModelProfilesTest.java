package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.domain.service.ai.model.ModelProfiles;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.*;

class ModelProfilesTest {
    @Test void existingProtocolsAndCapabilityDefaultsRemainUnchanged() {
        assertEquals(11,ModelProfiles.all().size());
        assertEquals(Set.of(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,ModelApiStyle.OPENAI_RESPONSES,
                ModelApiStyle.ANTHROPIC_MESSAGES,ModelApiStyle.DASHSCOPE_CHAT),
                ModelProfiles.all().stream().map(profile->profile.apiStyle()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("text-tools-v1",ModelProfiles.defaults(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"openai-chat-v1").capabilityProfile());
        assertEquals("qwen-tools-v1",ModelProfiles.defaults(ModelApiStyle.DASHSCOPE_CHAT,"qwen-chat-v1").capabilityProfile());
        assertEquals("responses-tools-v1",ModelProfiles.defaults(ModelApiStyle.OPENAI_RESPONSES,"openai-responses-v1").capabilityProfile());
        assertEquals("anthropic-tools-v1",ModelProfiles.defaults(ModelApiStyle.ANTHROPIC_MESSAGES,"anthropic-messages-v1").capabilityProfile());
    }
    @Test void everyAdvertisedThinkingModePassesTheSharedConfigurationValidator() {
        for(var profile:ModelProfiles.all()) {
            var connection=new Connection();connection.setApiStyle(profile.apiStyle());connection.setCompatibilityProfile(profile.compatibilityProfile());
            connection.setEndpoint("https://dashscope.aliyuncs.com/api/v1");
            for(var mode:profile.thinkingModes()) {
                var model=new Model();model.setCapabilityProfile(profile.capabilityProfile());
                var options=ModelProfiles.defaultOptions(profile);options.setThinkingMode(mode);
                // Mirrors the settings form: effort and budget only exist while thinking is on.
                if(mode!=ThinkingMode.ENABLED) { options.setReasoningEffort(null);options.setThinkingBudget(null); }
                if(mode==ThinkingMode.ENABLED && profile.optionRules().thinkingBudgetRequired())options.setThinkingBudget(profile.optionRules().minThinkingBudget());
                model.setDefaultOptions(options);
                assertDoesNotThrow(()->ProviderCompatibilityPolicy.validate(model,connection),profile.capabilityProfile()+"/"+mode);
            }
        }
    }
    @Test void sharedRulesRetainProviderSpecificBudgetEffortAndSamplingConstraints() {
        var qwen=ModelProfiles.find(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"qwen-chat-v1","qwen-tools-v1").optionRules();
        assertEquals(1,qwen.minThinkingBudget());assertFalse(qwen.thinkingBudgetRequired());
        assertTrue(qwen.temperatureModes().contains(ThinkingMode.ENABLED));
        var responses=ModelProfiles.find(ModelApiStyle.OPENAI_RESPONSES,"openai-responses-v1","responses-reasoning-v1").optionRules();
        assertEquals(List.of("low","medium","high"),responses.reasoningEfforts());assertEquals("medium",responses.defaultReasoningEffort());
        assertTrue(responses.temperatureModes().isEmpty());
        var anthropic=ModelProfiles.find(ModelApiStyle.ANTHROPIC_MESSAGES,"anthropic-messages-v1","anthropic-thinking-v1").optionRules();
        assertEquals(1024,anthropic.minThinkingBudget());assertTrue(anthropic.thinkingBudgetRequired());
        assertEquals(List.of(ThinkingMode.DISABLED),anthropic.temperatureModes());assertEquals(1,anthropic.maxTemperature());
    }
    @Test void genericCataloguePreservesSuggestionsAliasesAndLegacyLimits() {
        assertEquals(QwenModelSpecifications.all().stream().map(QwenModelSpecifications.Specification::modelId).toList(),
                ModelSpecifications.all("qwen-chat-v1").stream().map(ModelSpecifications.Specification::modelId).toList());
        assertTrue(ModelSpecifications.all("openai-chat-v1").isEmpty());
        assertEquals("qwen3.8-max",ModelSpecifications.find("QWEN3.8-MAX-0902","qwen-chat-v1").modelId());
        assertEquals(262144,ModelSpecifications.find("qwen3-max","qwen-chat-v1").contextTokens());
        assertNull(ModelSpecifications.find("qwen-plus","openai-chat-v1"));
        assertNull(ModelSpecifications.find("account-specific-model"));
        assertEquals(32768,ModelTokenPolicy.context("account-specific-model",null));
    }
    @Test void deepSeekKeepsThinkingSwitchableAndPublishesOfficialLimits() {
        var profile=ModelProfiles.defaults(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"deepseek-chat-v1");
        assertEquals("deepseek-thinking-tools-v1",profile.capabilityProfile());
        assertEquals(List.of(ThinkingMode.ENABLED,ThinkingMode.DISABLED),profile.thinkingModes());
        var rules=profile.optionRules();
        assertEquals(List.of("low","high","max"),rules.reasoningEfforts());assertEquals("high",rules.defaultReasoningEffort());
        assertEquals(List.of(ThinkingMode.DISABLED),rules.temperatureModes());assertTrue(rules.preserveThinking());
        for(var id:List.of("deepseek-flash","deepseek-v4-pro")) {
            var spec=ModelSpecifications.find(id,"deepseek-chat-v1");
            assertEquals(1_000_000,spec.contextTokens());assertEquals(393_216,spec.maxThinkingOutputTokens());
            assertEquals(1_000_000,ModelTokenPolicy.context(id,null));
        }
        assertEquals("deepseek-flash",ModelSpecifications.find("deepseek-v4-flash","deepseek-chat-v1").modelId());
        assertNull(ModelSpecifications.find("deepseek-flash","qwen-chat-v1"));
        // Compression follows the window: a small model keeps the old cadence, a 1M window is not cut at 24K.
        assertEquals(22938,ModelTokenPolicy.memoryThreshold(32_768));
        assertEquals(700_000,ModelTokenPolicy.memoryThreshold(1_000_000));
    }
}
