package edu.zsc.ai.api.model.request;

import jakarta.validation.constraints.*;

/** A per-message editor snapshot. Its SQL is context data and grants no tool authority. */
public record ChatWorkbenchContext(
        @NotBlank @Size(max=512) String documentId,
        @NotBlank @Size(max=256) String documentName,
        @NotNull @Min(0) Integer documentVersion,
        @NotBlank @Size(max=16000) String sql,
        boolean selected, boolean truncated,
        ChatDefaultTarget target) { }
