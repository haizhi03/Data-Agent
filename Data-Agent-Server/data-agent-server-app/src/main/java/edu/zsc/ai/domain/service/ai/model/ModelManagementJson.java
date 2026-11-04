package edu.zsc.ai.domain.service.ai.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;

public final class ModelManagementJson {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private ModelManagementJson() {}
    public static String write(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception e) { throw ModelManagementException.invalid(); }
    }
    public static <T> T read(String value, Class<T> type) {
        try { return JSON.readValue(value, type); } catch (Exception e) { throw ModelManagementException.unavailable(); }
    }
}
