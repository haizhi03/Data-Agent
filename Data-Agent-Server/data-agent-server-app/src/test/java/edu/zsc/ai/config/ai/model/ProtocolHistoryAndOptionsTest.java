package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.node.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.*;
import edu.zsc.ai.common.converter.ai.StoredMessageToResponseConverter;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.domain.model.entity.ai.StoredChatMessage;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static edu.zsc.ai.config.ai.model.ProtocolFixtures.*;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.*;

class ProtocolHistoryAndOptionsTest {
    @Test void incompatibleClosedHistoryIsPortableWithoutLeakingOpaqueState() {
        var source = config(ModelApiStyle.ANTHROPIC_MESSAGES);
        var target = config(ModelApiStyle.OPENAI_RESPONSES);
        AiMessage ai = decode(source, stateAndCalls(source.apiStyle(), "nonce", false));
        List<ChatMessage> messages = List.of(UserMessage.from("question"), ai, ToolExecutionResultMessage.from("call-a", "echo", "nonce"), AiMessage.from("done"), UserMessage.from("next"));
        var portable = ProtocolHistory.forTarget(messages, target);
        assertTrue(((AiMessage) portable.get(1)).attributes().isEmpty());
        assertEquals(ai.toolExecutionRequests(), ((AiMessage) portable.get(1)).toolExecutionRequests());
        assertEquals(messages.get(2), portable.get(2));
        assertFalse(ChatMessageSerializer.messagesToJson(portable).contains("fixture-signature"));
        assertEquals(ai, ProtocolHistory.forTarget(messages, source).get(1));
        assertThrows(ModelIntegrationException.class, () -> ProtocolHistory.forTarget(messages.subList(0, 2), target));
    }

    @Test void codecRejectsUnknownVersionsCorruptedProjectionAndCorruptedRawBlocksButHistoryStillDisplays() {
        var config = config(ModelApiStyle.ANTHROPIC_MESSAGES);
        AiMessage original = decode(config, text(config.apiStyle(), "visible text"));
        var converter = new StoredMessageToResponseConverter();
        assertEquals("visible text", converter.toResponse(StoredChatMessage.builder().id(1L).build(), original).getContent());
        var encoded = ProtocolJson.parse(original.attributes().get(ProtocolStateCodec.ATTRIBUTE).toString());
        ((ObjectNode) encoded).put("version", 2);
        AiMessage unknown = original.toBuilder().attributes(Map.of(ProtocolStateCodec.ATTRIBUTE, encoded.toString())).build();
        assertEquals("visible text", converter.toResponse(StoredChatMessage.builder().id(1L).build(), unknown).getContent());
        assertThrows(ModelIntegrationException.class, () -> ProtocolHistory.forTarget(List.of(unknown), config));
        ((ObjectNode) encoded).put("version", new java.math.BigInteger("18446744073709551617"));
        AiMessage overflowVersion = original.toBuilder().attributes(Map.of(ProtocolStateCodec.ATTRIBUTE, encoded.toString())).build();
        assertThrows(ModelIntegrationException.class, () -> ProtocolStateCodec.read(overflowVersion));
        assertThrows(ModelIntegrationException.class, () -> ProtocolStateCodec.read(original.withText("changed")));
        ObjectNode damaged = (ObjectNode) ProtocolJson.parse(original.attributes().get(ProtocolStateCodec.ATTRIBUTE).toString());
        ((ObjectNode) damaged.path("blocks").get(0)).put("text", "changed raw block");
        AiMessage corruption = original.toBuilder().attributes(Map.of(ProtocolStateCodec.ATTRIBUTE, damaged.toString())).build();
        assertThrows(ModelIntegrationException.class, () -> ProtocolStateCodec.read(corruption));
    }

    @Test void opaqueStateIsAbsentFromSummaryAndPublicHistoryBlocks() {
        var config = config(ModelApiStyle.ANTHROPIC_MESSAGES);
        AiMessage ai = decode(config, stateAndCalls(config.apiStyle(), "nonce", false));
        var messages = List.<ChatMessage>of(UserMessage.from("question"), ai, ToolExecutionResultMessage.from("call-a", "echo", "nonce"));
        String summaryInput = ChatMessageSerializer.messagesToJson(ProtocolHistory.forSummary(messages));
        assertTrue(summaryInput.contains("call-a")); assertTrue(summaryInput.contains("nonce"));
        assertFalse(summaryInput.contains("private-A")); assertFalse(summaryInput.contains("signature")); assertFalse(summaryInput.contains("redacted"));
        var publicMessage = new StoredMessageToResponseConverter().toResponse(StoredChatMessage.builder().id(1L).build(), ai);
        assertTrue(publicMessage.getBlocks().stream().noneMatch(block -> "THOUGHT".equals(block.getType())));
        assertTrue(publicMessage.getBlocks().stream().noneMatch(block -> block.getData().contains("signature") || block.getData().contains("redacted")));
    }

    @Test void compressionNeverSplitsAnUnclosedToolGroupOrOpaqueTurn() {
        var config = config(ModelApiStyle.OPENAI_RESPONSES);
        AiMessage ai = decode(config, stateAndCalls(config.apiStyle(), "nonce", true));
        var messages = List.<ChatMessage>of(UserMessage.from("question"), ai,
                ToolExecutionResultMessage.from("call-a", "echo", "nonce"), ToolExecutionResultMessage.from("call-b", "echo", "nonce"),
                AiMessage.from("done"), UserMessage.from("next"));
        assertEquals(0, ProtocolHistory.safeSplit(messages, 3));
        assertEquals(0, ProtocolHistory.safeSplit(messages, 4));
        assertEquals(5, ProtocolHistory.safeSplit(messages, 5));
        var unknown = ToolExecutionResultMessage.from("unknown", "echo", "nonce");
        assertThrows(ModelIntegrationException.class, () -> ProtocolHistory.checkClosed(List.of(UserMessage.from("q"), ai, unknown)));
        assertThrows(ModelIntegrationException.class, () -> ProtocolHistory.checkClosed(List.of(UserMessage.from("q"), ai, UserMessage.from("skip tools"))));
        assertThrows(ModelIntegrationException.class, () -> ProtocolHistory.checkClosed(List.of(ToolExecutionResultMessage.builder().id("unknown").text("unpaired").build())));
        var one = AiMessage.from(ToolExecutionRequest.builder().id("reused").name("echo").arguments("{}").build());
        var result = ToolExecutionResultMessage.from("reused", "echo", "ok");
        assertDoesNotThrow(() -> ProtocolHistory.checkClosed(List.of(UserMessage.from("first"), one, result, UserMessage.from("second"), one, result)));
    }

    @Test void credentialConnectionAndThinkingChangesDoNotReplayOldProviderState() {
        var source = config(ModelApiStyle.OPENAI_RESPONSES);
        AiMessage ai = decode(source, text(source.apiStyle(), "old answer"));
        var rotated = new ResolvedModelConfig(source.modelConfigId(), 2, source.connectionId(), 2, source.access(), source.provider(), source.apiStyle(),
                source.modelId(), source.name(), source.endpoint(), source.credentialRef(), 2, source.capabilityProfile(), source.compatibilityProfile(), source.options(),
                source.maxContextTokens(), source.memoryThreshold(), true, Set.of());
        assertTrue(((AiMessage) ProtocolHistory.forTarget(List.of(ai), rotated).get(0)).attributes().isEmpty());
    }

    @Test void schemaMappingPreservesRequiredEnumsReferencesAndAdditionalProperties() {
        var definition = JsonObjectSchema.builder().addStringProperty("value").required("value").additionalProperties(false).build();
        var schema = JsonObjectSchema.builder().addProperty("mode", JsonEnumSchema.builder().enumValues(List.of("read", "write")).build())
                .addProperty("record", JsonReferenceSchema.builder().reference("Record").build()).required("mode")
                .definitions(Map.of("Record", definition)).additionalProperties(false).build();
        var encoded = ToolSchemaMapper.map(schema);
        assertEquals(List.of("mode"), ProtocolJson.MAPPER.convertValue(encoded.get("required"), List.class));
        assertFalse(encoded.path("additionalProperties").asBoolean(true));
        assertEquals("#/$defs/Record", encoded.path("properties").path("record").path("$ref").asText());
        assertFalse(encoded.path("$defs").path("Record").path("additionalProperties").asBoolean(true));
        assertThrows(ModelIntegrationException.class, () -> ToolSchemaMapper.map(() -> "unknown schema"));
    }

    @Test void versionedProfilesValidateThinkingAndSamplingCombinationsBeforeStartup() {
        Connection connection = new Connection(); connection.setApiStyle(ModelApiStyle.OPENAI_RESPONSES); connection.setCompatibilityProfile("openai-responses-v1");
        Model model = new Model(); model.setCapabilityProfile("responses-reasoning-v1");
        assertThrows(IllegalStateException.class, () -> ProviderCompatibilityPolicy.validate(model, connection));
        model.getDefaultOptions().setThinkingMode(ThinkingMode.ENABLED); model.getDefaultOptions().setReasoningEffort("high");
        assertDoesNotThrow(() -> ProviderCompatibilityPolicy.validate(model, connection));
        assertEquals("high", ProviderCompatibilityPolicy.effectiveReasoningEffort(model, connection));
        model.getDefaultOptions().setReasoningEffort(null);
        assertEquals("medium", ProviderCompatibilityPolicy.effectiveReasoningEffort(model, connection));
        model.getDefaultOptions().setTemperature(0.5);
        assertThrows(IllegalStateException.class, () -> ProviderCompatibilityPolicy.validate(model, connection));
        model.getDefaultOptions().setTemperature(null); model.getDefaultOptions().setReasoningEffort("arbitrary");
        assertThrows(IllegalStateException.class, () -> ProviderCompatibilityPolicy.validate(model, connection));
        connection.setApiStyle(ModelApiStyle.ANTHROPIC_MESSAGES); connection.setCompatibilityProfile("anthropic-messages-v1"); model.setCapabilityProfile("anthropic-thinking-v1");
        model.getDefaultOptions().setReasoningEffort(null); model.getDefaultOptions().setThinkingBudget(512);
        assertThrows(IllegalStateException.class, () -> ProviderCompatibilityPolicy.validate(model, connection));
        model.getDefaultOptions().setThinkingBudget(2048);
        assertDoesNotThrow(() -> ProviderCompatibilityPolicy.validate(model, connection));
        model.setCapabilityProfile("anthropic-adaptive-v1"); model.getDefaultOptions().setThinkingMode(ThinkingMode.ADAPTIVE); model.getDefaultOptions().setThinkingBudget(null);
        assertDoesNotThrow(() -> ProviderCompatibilityPolicy.validate(model, connection));
        assertTrue(ProviderCompatibilityPolicy.supportsThinking(model.getCapabilityProfile()));
    }

    @Test void requestOptionsAreExplicitAndUnsupportedOptionsFailBeforeHttp() {
        var credentials = mock(CredentialService.class); var http = mock(HttpClient.class);
        var responses = new ResponsesProtocolModel(config(ModelApiStyle.OPENAI_RESPONSES), credentials, http);
        assertThrows(ModelIntegrationException.class, () -> responses.chat(ChatRequest.builder().messages(UserMessage.from("q")).topP(0.1).build()));
        assertThrows(ModelIntegrationException.class, () -> responses.chat(ChatRequest.builder().messages(UserMessage.from("q")).maxOutputTokens(100).build()));
        verifyNoInteractions(credentials, http);
        var anthropic = new AnthropicMessagesProtocolModel(config(ModelApiStyle.ANTHROPIC_MESSAGES), credentials, http);
        var body = anthropic.encode(ChatRequest.builder().messages(SystemMessage.from("s1"), SystemMessage.from("s2"), UserMessage.from("q")).build(), false);
        assertEquals(2, body.path("system").size()); assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals(4096, body.path("max_tokens").asInt());
        assertThrows(ModelIntegrationException.class, () -> anthropic.encode(ChatRequest.builder().messages(UserMessage.from("q")).toolChoice(ToolChoice.REQUIRED).build(), false));
    }

    @Test void missingUsageStaysUnknownAndTruncationIsDifferentFromFiltering() {
        var config = config(ModelApiStyle.OPENAI_RESPONSES); var model = new ResponsesProtocolModel(config, mock(CredentialService.class), mock(HttpClient.class));
        var response = response(config.apiStyle(), text(config.apiStyle(), "answer"), "answer"); response.remove("usage");
        assertNull(model.decode(response).tokenUsage());
        response.put("status", "incomplete").putObject("incomplete_details").put("reason", "content_filter");
        assertEquals("MODEL_REFUSED", assertThrows(ModelIntegrationException.class, () -> model.decode(response)).getReason());
    }

    @Test void errorToolResultsKeepTheirCallIdentityAndErrorMeaningInBothProtocols() {
        var error = ToolExecutionResultMessage.builder().id("call-a").toolName("echo").text("probe failed").isError(true).build();
        for (var style : List.of(ModelApiStyle.OPENAI_RESPONSES, ModelApiStyle.ANTHROPIC_MESSAGES)) {
            var config = config(style); AiMessage ai = decode(config, stateAndCalls(style, "nonce", false));
            var request = ChatRequest.builder().messages(UserMessage.from("probe"), ai, error).build();
            if (style == ModelApiStyle.OPENAI_RESPONSES) {
                var body = new ResponsesProtocolModel(config, null, null).encode(request, false);
                var output = body.path("input").get(body.path("input").size() - 1);
                assertEquals("call-a", output.path("call_id").asText());
                assertTrue(ProtocolJson.parse(output.path("output").asText()).path("is_error").asBoolean());
            } else {
                var body = new AnthropicMessagesProtocolModel(config, null, null).encode(request, false);
                var output = body.path("messages").get(body.path("messages").size() - 1).path("content").get(0);
                assertEquals("call-a", output.path("tool_use_id").asText()); assertTrue(output.path("is_error").asBoolean());
            }
        }
    }

    private static AiMessage decode(ResolvedModelConfig config, ArrayNode blocks) {
        var response = response(config.apiStyle(), blocks, "fixture-response");
        return (config.apiStyle() == ModelApiStyle.OPENAI_RESPONSES ? new ResponsesProtocolModel(config, null, null)
                : new AnthropicMessagesProtocolModel(config, null, null)).decode(response).aiMessage();
    }
    private static ResolvedModelConfig config(ModelApiStyle style) {
        return new ResolvedModelConfig("config", 1, "connection-" + style, 1, new ModelAccessContext(1, WorkspaceTypeEnum.PERSONAL, 1), "FIXTURE", style,
                "fixture-model", "Fixture", URI.create("https://example.com/v1/"), "env:FIXTURE_KEY", 1,
                style == ModelApiStyle.OPENAI_RESPONSES ? "responses-tools-v1" : "anthropic-tools-v1",
                style == ModelApiStyle.OPENAI_RESPONSES ? "openai-responses-v1" : "anthropic-messages-v1",
                new ResolvedModelConfig.EffectiveOptions(ThinkingMode.DISABLED, null, null, 4096), 32768, 24000, true, Set.of());
    }
}
