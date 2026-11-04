package edu.zsc.ai.domain.model.dto.request.db;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Reloads one schema ER diagram for a chosen batch of tables.
 * {@code all} draws every table and ignores {@code tables}, so a full schema
 * does not send hundreds of names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErDiagramRequest {

    @NotNull(message = "connectionId is required")
    private Long connectionId;

    private String catalog;

    private String schema;

    /** Every table in this schema. */
    @JsonProperty("all")
    private boolean all;

    /** Selected table names. Ignored when {@link #all} is true. */
    private List<String> tables;
}
