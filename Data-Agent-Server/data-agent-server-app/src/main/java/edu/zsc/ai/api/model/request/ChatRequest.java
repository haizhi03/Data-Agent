package edu.zsc.ai.api.model.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Chat Request DTO
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatRequest extends BaseRequest {

    @NotBlank(message = "Message cannot be empty")
    private String message;

    /**
     * Model name for chat, e.g. qwen3.6-max-preview or qwen3-max-2026-01-23.
     * Optional; server defaults to ai.models.default-model when blank.
     */
    private String model;

    /** Internal server configuration ID; a supplied legacy name must identify the same configuration. */
    private String modelConfigId;

    /** Optional per-message main-chat thinking override; capabilities are validated server-side. */
    private edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode thinkingMode;

    /**
     * Preferred prompt language, e.g. "en" or "zh".
     * Optional; server defaults to English prompt when blank/unknown.
     */
    private String language;

    /**
     * Agent mode, e.g. "Agent" or "Plan".
     * Optional; server defaults to Agent mode when blank/unknown.
     */
    private String agentType;

    /**
     * Database catalog name used by chat runtime context.
     */
    private String catalogName;

    /**
     * Structured @ mentions selected from ChatInput.
     * These are used to populate the runtime prompt's explicit references block.
     */
    private List<ChatUserMention> userMentions;

    /** Canonical schema field; schema remains a backwards-compatible input alias. */
    private String schemaName;

    /** Explicit per-turn snapshot. {} means unspecified; omission uses the saved conversation target. */
    private ChatDefaultTarget defaultTarget;

    @Override
    @JsonIgnore
    public String getSchema() { return schemaName; }

    @Override
    @JsonSetter("schema")
    public void setSchema(String schema) { if (schemaName == null) schemaName = schema; }

    /**
     * Optional client workspace fallback when custom HTTP headers are missing on the chat stream request
     * (e.g. some fetch/EventSource paths). Values are validated with {@code loadActiveMembership}; if the
     * interceptor already set {@code ORGANIZATION} with {@code orgId}, body hints are ignored.
     */
    private String clientWorkspaceType;

    private Long clientOrgId;

    @Override
    @JsonIgnore
    public String getCatalog() {
        return catalogName;
    }

    @Override
    @JsonSetter("catalog")
    public void setCatalog(String catalog) {
        if (catalogName == null) this.catalogName = catalog;
    }
}
