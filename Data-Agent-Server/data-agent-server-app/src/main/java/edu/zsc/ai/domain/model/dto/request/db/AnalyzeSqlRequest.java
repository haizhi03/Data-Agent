package edu.zsc.ai.domain.model.dto.request.db;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AnalyzeSqlRequest(@NotNull Long connectionId, String catalog, String schema,
                                @NotBlank String sql) {
}
