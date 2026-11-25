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
    @Test void kimiRequiresThinkingAndOnlyAcceptsItsOwnEffortsAndParameters() {
        var connection=new Connection();connection.setApiStyle(ModelApiStyle.OPENAI_CHAT_COMPLETIONS);connection.setCompatibilityProfile("kimi-chat-v1");
        var model=new Model();model.setCapabilityProfile("kimi-reasoning-tools-v1");
        assertThrows(IllegalStateException.class,()->ProviderCompatibilityPolicy.validate(model,connection));
        model.setDefaultOptions(ModelProfiles.defaultOptions(ModelProfiles.defaults(connection.getApiStyle(),connection.getCompatibilityProfile())));
        for(var effort:List.of("low","high","max")) {
            model.getDefaultOptions().setReasoningEffort(effort);
            assertDoesNotThrow(()->ProviderCompatibilityPolicy.validate(model,connection));
        }
        model.getDefaultOptions().setReasoningEffort("medium");
        assertThrows(IllegalStateException.class,()->ProviderCompatibilityPolicy.validate(model,connection));
        model.getDefaultOptions().setReasoningEffort(null);
        assertEquals("max",ProviderCompatibilityPolicy.effectiveReasoningEffort(model,connection));
        model.getDefaultOptions().setThinkingBudget(1024);
        assertThrows(IllegalStateException.class,()->ProviderCompatibilityPolicy.validate(model,connection));
        model.getDefaultOptions().setThinkingBudget(null);model.getDefaultOptions().setTemperature(1.0);
        assertThrows(IllegalStateException.class,()->ProviderCompatibilityPolicy.validate(model,connection));
    }
    @Test void catalogueScopesCandidatesAndLimitsToTheirCompatibilityProfile() {
        assertEquals(List.of("kimi-k3.1-pro","kimi-k3"),ModelSpecifications.all("kimi-chat-v1").stream().map(ModelSpecifications.Specification::modelId).toList());
        assertEquals(4,ModelSpecifications.all("qwen-chat-v1").size());
        assertNull(ModelSpecifications.find("kimi-k3","qwen-chat-v1"));
        assertEquals(1_048_576,ModelTokenPolicy.context("kimi-k3",null));
        for(var purpose:ModelPurpose.values())assertEquals(16384,ModelTokenPolicy.output("kimi-k3","kimi-chat-v1",null,1_048_576,ThinkingMode.ENABLED,null,purpose));
    }
}
