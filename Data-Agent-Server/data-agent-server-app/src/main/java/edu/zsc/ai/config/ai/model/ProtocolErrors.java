package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

final class ProtocolErrors {
    private ProtocolErrors() {}
    static ModelIntegrationException safe(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof ModelIntegrationException known) return known;
            if (error instanceof java.io.InterruptedIOException || error instanceof dev.langchain4j.exception.TimeoutException)
                return new ModelIntegrationException("MODEL_UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
        }
        return ProtocolJson.invalid();
    }
    static ModelIntegrationException incomplete(JsonNode details) {
        return new ModelIntegrationException(switch (details.path("reason").asText()) {
            case "max_output_tokens" -> "MODEL_OUTPUT_TRUNCATED";
            case "content_filter" -> "MODEL_REFUSED";
            default -> "MODEL_PROTOCOL_ERROR";
        }, HttpStatus.BAD_GATEWAY);
    }
    static ModelIntegrationException from(JsonNode error) {
        String type = error.path("code").asText(error.path("type").asText());
        String code = switch (type) {
            case "authentication_error", "invalid_api_key", "permission_error" -> "MODEL_AUTH_FAILED";
            case "rate_limit_error", "rate_limit_exceeded", "overloaded_error" -> "MODEL_RATE_LIMITED";
            case "timeout_error", "request_timeout" -> "MODEL_UPSTREAM_TIMEOUT";
            case "refusal", "content_filter", "content_policy_violation" -> "MODEL_REFUSED";
            default -> "MODEL_PROTOCOL_ERROR";
        };
        return new ModelIntegrationException(code, HttpStatus.BAD_GATEWAY);
    }
}
