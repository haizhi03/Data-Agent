package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.langchain4j.data.message.AiMessage;
import java.util.*;

/** Versioned metadata inside SDK AiMessage.attributes; the outer SDK message JSON remains unchanged. */
public final class ProtocolStateCodec {
    public static final String ATTRIBUTE = "data_agent_protocol_state";
    private ProtocolStateCodec() {}

    static AiMessage attach(AiMessage message, ResolvedModelConfig config, ArrayNode rawBlocks) {
        var state = ProtocolJson.object().put("version", 1).put("apiStyle", config.apiStyle().name())
                .put("connectionId", config.connectionId()).put("modelId", config.modelId())
                .put("endpoint", config.endpoint().toString()).put("ownerId", Long.toString(config.access().ownerId()))
                .put("workspaceType", config.access().workspaceType().name())
                .put("connectionVersion", config.connectionVersion()).put("credentialVersion", config.credentialVersion())
                .put("compatibilityProfile", config.compatibilityProfile()).put("capabilityProfile", config.capabilityProfile()).put("thinkingMode", config.options().thinkingMode().name())
                .put("optionsDigest", optionsDigest(config))
                .put("projectionDigest", projectionDigest(message)).put("blocksDigest", digest(rawBlocks.toString()));
        state.set("blocks", rawBlocks.deepCopy());
        Map<String, Object> attributes = new HashMap<>(message.attributes());
        // Store an immutable string, not a mutable Jackson tree or a secret-bearing SDK object.
        attributes.put(ATTRIBUTE, state.toString());
        return message.toBuilder().attributes(Map.copyOf(attributes)).build();
    }

    static JsonNode read(AiMessage message) {
        Object encoded = message.attributes().get(ATTRIBUTE);
        if (encoded == null) return null;
        try {
            if (!(encoded instanceof String json)) throw ProtocolJson.incompatible();
            JsonNode state = ProtocolJson.parse(json);
            if (!state.isObject() || !state.path("version").isIntegralNumber() || !state.path("version").canConvertToLong()
                    || state.path("version").longValue() != 1 || !state.path("blocks").isArray()) throw ProtocolJson.incompatible();
            for (String field : List.of("apiStyle", "connectionId", "modelId", "endpoint", "ownerId", "workspaceType", "compatibilityProfile", "capabilityProfile", "thinkingMode", "optionsDigest")) ProtocolJson.text(state, field);
            for (String field : List.of("connectionVersion", "credentialVersion")) {
                if (!state.path(field).isIntegralNumber() || !state.path(field).canConvertToLong() || state.path(field).longValue() < 1) throw ProtocolJson.incompatible();
            }
            ModelApiStyle.valueOf(state.path("apiStyle").asText());
            if (!projectionDigest(message).equals(state.path("projectionDigest").asText())
                    || !digest(state.path("blocks").toString()).equals(state.path("blocksDigest").asText())) throw ProtocolJson.incompatible();
            return state;
        } catch (RuntimeException e) { throw ProtocolJson.incompatible(); }
    }

    static boolean matches(JsonNode state, ResolvedModelConfig config) {
        return state.path("apiStyle").asText().equals(config.apiStyle().name())
                && state.path("connectionId").asText().equals(config.connectionId())
                && state.path("modelId").asText().equals(config.modelId())
                && state.path("endpoint").asText().equals(config.endpoint().toString())
                && state.path("ownerId").asText().equals(Long.toString(config.access().ownerId()))
                && state.path("workspaceType").asText().equals(config.access().workspaceType().name())
                && state.path("connectionVersion").asLong(-1) == config.connectionVersion()
                && state.path("credentialVersion").asLong(-1) == config.credentialVersion()
                && state.path("compatibilityProfile").asText().equals(config.compatibilityProfile())
                && state.path("capabilityProfile").asText().equals(config.capabilityProfile())
                && state.path("optionsDigest").asText().equals(optionsDigest(config))
                && state.path("thinkingMode").asText().equals(config.options().thinkingMode().name());
    }

    static ArrayNode blocks(AiMessage message, ResolvedModelConfig config) {
        JsonNode state = read(message);
        if (state == null) return null;
        if (!matches(state, config)) throw ProtocolJson.incompatible();
        return ((ArrayNode) state.get("blocks")).deepCopy();
    }

    public static AiMessage portable(AiMessage message) {
        Map<String, Object> attributes = new HashMap<>(message.attributes());
        attributes.remove(ATTRIBUTE);
        attributes.remove("thinking_signature");
        attributes.remove("redacted_thinking");
        attributes.remove("server_tool_results");
        return message.toBuilder().thinking(null).attributes(Map.copyOf(attributes)).build();
    }
    private static String projectionDigest(AiMessage message) {
        var projection = ProtocolJson.object();
        if (message.text() != null) projection.put("text", message.text());
        var calls = projection.putArray("calls");
        for (var call : message.toolExecutionRequests()) calls.add(ProtocolJson.object().put("id", call.id()).put("name", call.name()).put("arguments", call.arguments()));
        return digest(projection.toString());
    }
    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String optionsDigest(ResolvedModelConfig config) {
        return digest(ProtocolJson.MAPPER.valueToTree(config.options()).toString());
    }
}
