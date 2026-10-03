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
        /** First foreign-key column. Composite keys also fill {@link #fromColumns}. */
        private String fromColumn;
        private List<String> fromColumns;
        private String toCatalog;
        private String toSchema;
        private String toTable;
        /** First referenced column. Composite keys also fill {@link #toColumns}. */
        private String toColumn;
        private List<String> toColumns;
        /**
         * ONE_TO_ONE, ONE_TO_MANY, or MANY_TO_MANY. For a foreign key the referenced
         * end is one, and ONE_TO_MANY means the foreign-key end is many.
         * MANY_TO_MANY connects the two tables referenced by a junction table.
         */
        private String cardinality;
        /** Junction table for MANY_TO_MANY. Null for a foreign key. */
        private String viaCatalog;
        private String viaSchema;
        private String viaTable;
    }
}
