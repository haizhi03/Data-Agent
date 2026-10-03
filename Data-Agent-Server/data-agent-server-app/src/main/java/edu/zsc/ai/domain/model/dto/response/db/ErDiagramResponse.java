package edu.zsc.ai.domain.model.dto.response.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Schema-level ER diagram shared by every database plugin.
 * A table is catalog, schema, and name. Each relation end carries the same pair.
 * Tables outside the opened schema are marked external and are not expanded further.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErDiagramResponse {

    private List<ErTable> tables;
    private List<ErRelation> relations;
    private boolean truncated;
    /** External tables were capped. Relations past the cap are omitted. */
    private boolean externalTruncated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErTable {
        private String name;
        private String catalog;
        private String schema;
        /** Table lives outside the schema this diagram was opened for. */
        private boolean external;
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
        private String fromCatalog;
        private String fromSchema;
        private String fromTable;
        private String fromColumn;
        private String toCatalog;
        private String toSchema;
        private String toTable;
        private String toColumn;
    }
}
