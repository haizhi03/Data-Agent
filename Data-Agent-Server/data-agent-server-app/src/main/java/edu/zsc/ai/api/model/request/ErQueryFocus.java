package edu.zsc.ai.api.model.request;

import java.util.List;
import lombok.Data;

/**
 * Tables the user circled on the ER diagram, plus the foreign keys that touch them.
 * This is the subject of one question, not a closed set of tables.
 */
@Data
public class ErQueryFocus {

    private Long connectionId;

    private String connectionName;

    private String catalogName;

    private String schemaName;

    private List<Table> tables;

    private List<Relation> insideRelations;

    private List<Relation> outsideRelations;

    @Data
    public static class Table {
        private String name;
        private String catalog;
        private String schema;
        private List<Column> columns;
    }

    @Data
    public static class Column {
        private String name;
        private String typeName;
        private boolean primaryKey;
    }

    @Data
    public static class Relation {
        private String fromCatalog;
        private String fromSchema;
        private String fromTable;
        private List<String> fromColumns;
        private String toCatalog;
        private String toSchema;
        private String toTable;
        private List<String> toColumns;
        private String cardinality;
        private String viaCatalog;
        private String viaSchema;
        private String viaTable;
    }
}
