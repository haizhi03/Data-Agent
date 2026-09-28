package edu.zsc.ai.domain.model.dto.response.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Schema-level ER diagram shared by every database plugin.
 * Tables and relations are already normalized; the client does not branch on db type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErDiagramResponse {

    private List<ErTable> tables;
    private List<ErRelation> relations;
    private boolean truncated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErTable {
        private String name;
        private String comment;
        private List<ErColumn> columns;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErColumn {
        private String name;
        private String typeName;
        private String comment;
        private boolean primaryKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErRelation {
        private String name;
        private String fromTable;
        private String fromColumn;
        private String toTable;
        private String toColumn;
    }
}
