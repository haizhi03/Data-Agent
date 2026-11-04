package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;

/** Strict, bounded-by-transport JSON parsing with no upstream payloads in exceptions. */
final class ProtocolJson {
    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private ProtocolJson() {}
    static JsonNode parse(String value) {
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null) throw invalid();
            return node;
        } catch (Exception e) { throw invalid(); }
    }
    static ObjectNode object() { return MAPPER.createObjectNode(); }
    static ArrayNode array() { return MAPPER.createArrayNode(); }
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }
    static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }
    static Integer count(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid();
        return value.intValue();
    }
    static ModelIntegrationException invalid() { return new ModelIntegrationException("MODEL_PROTOCOL_ERROR", HttpStatus.BAD_GATEWAY); }
    static ModelIntegrationException incompatible() { return new ModelIntegrationException("MODEL_PROVIDER_STATE_INCOMPATIBLE", HttpStatus.BAD_REQUEST); }
}
