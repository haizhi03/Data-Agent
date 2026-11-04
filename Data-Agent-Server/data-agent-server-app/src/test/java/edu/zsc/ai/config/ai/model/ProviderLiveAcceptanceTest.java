package edu.zsc.ai.config.ai.model;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.*;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.QwenProperties;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.*;

/** Explicit opt-in only. Uses real billable services and no databases or external tools. */
@EnabledIfEnvironmentVariable(named = "MODEL_PROVIDER_LIVE_TESTS", matches = "true")
@Timeout(180)
class ProviderLiveAcceptanceTest {
    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void realTextStreamToolAndSerializedContinuation(ModelApiStyle style) throws Exception {
        String prefix = style == ModelApiStyle.OPENAI_RESPONSES ? "OPENAI_RESPONSES" : "ANTHROPIC_MESSAGES";
        var config = config(style, prefix);
        var credentials = new CredentialService(new StandardEnvironment(), new QwenProperties());
        ModelClientFactory factory = style == ModelApiStyle.OPENAI_RESPONSES ? new ResponsesClientFactory(credentials) : new AnthropicMessagesClientFactory(credentials);
        var clients = factory.create(config);
        String nonce = UUID.randomUUID().toString();
        assertFalse(clients.chatModel().chat("Reply with OK only.").isBlank()); // L1
        CompletableFuture<ChatResponse> streamed = new CompletableFuture<>();
        clients.streamingChatModel().chat(ChatRequest.builder().messages(UserMessage.from("Reply with READY only.")).build(), new StreamingChatResponseHandler() {
            public void onCompleteResponse(ChatResponse response) { streamed.complete(response); }
            public void onError(Throwable error) { streamed.completeExceptionally(error); }
        });
        assertFalse(streamed.get(60, TimeUnit.SECONDS).aiMessage().text().isBlank()); // L2
        var tool = ToolSpecification.builder().name("echo_probe").description("Return the server-provided nonce exactly; no external effects")
                .parameters(JsonObjectSchema.builder().addStringProperty("nonce").required("nonce").additionalProperties(false).build()).build();
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("Call echo_probe with nonce " + nonce + ", then include its returned nonce exactly in your answer."));
        // Thinking + forced tool selection is unsupported by some Messages models; verify their actual tool choice instead.
        ToolChoice choice = style == ModelApiStyle.ANTHROPIC_MESSAGES && config.options().thinkingMode() != ThinkingMode.DISABLED ? ToolChoice.AUTO : ToolChoice.REQUIRED;
        var toolResponse = clients.chatModel().chat(ChatRequest.builder().messages(history).toolSpecifications(tool).toolChoice(choice).build());
        assertEquals(1, toolResponse.aiMessage().toolExecutionRequests().size(), "The model must actually choose the probe tool");
        var call = toolResponse.aiMessage().toolExecutionRequests().get(0);
        assertEquals("echo_probe", call.name()); assertEquals(nonce, ProtocolJson.parse(call.arguments()).path("nonce").asText());
        history.add(toolResponse.aiMessage());
        history.add(ToolExecutionResultMessage.from(call.id(), call.name(), nonce)); // L3: execute exactly this local echo, once.
        List<ChatMessage> restored = ChatMessageDeserializer.messagesFromJson(ChatMessageSerializer.messagesToJson(history));
        var reconstructed = factory.create(config);
        var answer = reconstructed.chatModel().chat(ChatRequest.builder().messages(restored).toolSpecifications(tool).toolChoice(ToolChoice.NONE).build());
        assertTrue(answer.aiMessage().text().contains(nonce)); // L4: full output items/signatures round-trip without replaying a tool.
    }

    private static ResolvedModelConfig config(ModelApiStyle style, String prefix) {
        String key = style == ModelApiStyle.OPENAI_RESPONSES ? "OPENAI_API_KEY" : "ANTHROPIC_API_KEY";
        required(key); String modelId = required(prefix + "_MODEL_ID");
        Connection connection = new Connection(); connection.setApiStyle(style);
        connection.setCompatibilityProfile(style == ModelApiStyle.OPENAI_RESPONSES ? "openai-responses-v1" : "anthropic-messages-v1");
        connection.setEndpoint(System.getenv().getOrDefault(prefix + "_BASE_URL", style == ModelApiStyle.OPENAI_RESPONSES ? "https://api.openai.com/v1" : "https://api.anthropic.com/v1"));
        Model model = new Model();
        model.setCapabilityProfile(System.getenv().getOrDefault(prefix + "_CAPABILITY_PROFILE", style == ModelApiStyle.OPENAI_RESPONSES ? "responses-tools-v1" : "anthropic-tools-v1"));
        model.getDefaultOptions().setThinkingMode(ThinkingMode.valueOf(System.getenv().getOrDefault(prefix + "_THINKING_MODE", "DISABLED")));
        model.getDefaultOptions().setMaxOutputTokens(Integer.valueOf(System.getenv().getOrDefault(prefix + "_MAX_OUTPUT_TOKENS", "4096")));
        String budget = System.getenv(prefix + "_THINKING_BUDGET"); if (budget != null) model.getDefaultOptions().setThinkingBudget(Integer.valueOf(budget));
        model.getDefaultOptions().setReasoningEffort(System.getenv(prefix + "_REASONING_EFFORT"));
        ProviderCompatibilityPolicy.validate(model, connection);
        var options = model.getDefaultOptions();
        return new ResolvedModelConfig("live-" + prefix, 1, "live-" + prefix, 1, new ModelAccessContext(1, WorkspaceTypeEnum.PERSONAL, 1),
                prefix, style, modelId, "Acceptance probe", ModelEndpointPolicy.normalize(connection.getEndpoint()), "env:" + key, 1,
                model.getCapabilityProfile(), connection.getCompatibilityProfile(),
                new ResolvedModelConfig.EffectiveOptions(options.getThinkingMode(), options.getThinkingBudget(), null, options.getMaxOutputTokens(), ProviderCompatibilityPolicy.effectiveReasoningEffort(model, connection)),
                32768, 24000, true, Set.of());
    }
    private static String required(String name) {
        String value = System.getenv(name); assertTrue(value != null && !value.isBlank(), "Required server environment variable: " + name); return value;
    }
}
