package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

/** Client identity fields are hints to reject, never inputs to the trusted context. */
final class LocalScopeGuard {
    private static final Map<String, String> HEADERS = Map.of(
            "X-User-Id", "userId", "X-Owner-Id", "ownerId", "X-Workspace-Type", "workspaceType",
            "X-Org-Id", "orgId", "X-Org-User-Rel-Id", "orgUserRelId", "X-Org-Role", "orgRole");
    private static final java.util.Set<String> FIELDS = java.util.Set.of("userId", "ownerId", "workspaceType",
            "clientWorkspaceType", "orgId", "clientOrgId", "orgUserRelId", "orgRole");

    static void validateRequest(HttpServletRequest request) {
        HEADERS.forEach((header, field) -> Collections.list(request.getHeaders(header))
                .forEach(value -> validate(field, value)));
        FIELDS.forEach(field -> {
            String[] values = request.getParameterValues(field);
            if (values != null) for (String value : values) validate(field, value);
        });
    }

    static void validateBody(JsonNode body) {
        if (body == null || !body.isObject()) return;
        FIELDS.forEach(field -> {
            JsonNode value = body.get(field);
            if (value != null && !value.isNull()) {
                if (!value.isValueNode()) throw LocalRequestException.forbidden();
                validate(field, value.asText());
            }
        });
    }

    private static void validate(String field, String value) {
        if (value == null || value.isBlank()) return;
        boolean allowed = switch (field) {
            case "userId", "ownerId" -> "1".equals(value.trim());
            case "workspaceType", "clientWorkspaceType" -> "PERSONAL".equalsIgnoreCase(value.trim());
            default -> false;
        };
        if (!allowed) throw LocalRequestException.forbidden();
    }
}
