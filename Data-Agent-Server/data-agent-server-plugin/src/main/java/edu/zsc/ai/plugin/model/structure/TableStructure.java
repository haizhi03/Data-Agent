package edu.zsc.ai.plugin.model.structure;

import java.util.List;

/**
 * Snapshot of a table used by the structure editor.
 * {@code originalName} is the name loaded from the database; a different {@code name} is a rename.
 * A blank {@code originalName} means the object was added in the draft.
 */
public record TableStructure(
        String name,
        String comment,
        String engine,
        String charset,
        String collation,
        List<Column> columns,
        PrimaryKey primaryKey,
        List<Index> indexes,
        List<ForeignKey> foreignKeys,
        List<Check> checks
) {
    public record Column(
            String name,
            String originalName,
            String typeName,
            String columnType,
            Integer length,
            Integer scale,
            boolean nullable,
            boolean autoIncrement,
            boolean unsigned,
            String defaultKind,
            String defaultValue,
            String onUpdate,
            String comment,
            String charset,
            String collation
    ) {
    }

    public record PrimaryKey(String name, List<String> columns) {
    }

    public record Index(
            String name,
            String originalName,
            boolean unique,
            String type,
            List<String> columns,
            List<Integer> prefixLengths
    ) {
    }

    public record ForeignKey(
            String name,
            String originalName,
            List<String> columns,
            String referencedSchema,
            String referencedTable,
            List<String> referencedColumns,
            String onUpdate,
            String onDelete
    ) {
    }

    public record Check(String name, String originalName, String expression) {
    }
}
