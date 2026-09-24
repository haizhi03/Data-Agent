package edu.zsc.ai.plugin.model.metadata;

import java.util.List;

/** Columns retain dictionary POSITION order, including composite foreign keys. */
public record ConstraintMetadata(String name, String tableName, String type, List<String> columns,
                                 String referencedSchema, String referencedTable,
                                 List<String> referencedColumns, String checkExpression,
                                 boolean enabled) {
}
