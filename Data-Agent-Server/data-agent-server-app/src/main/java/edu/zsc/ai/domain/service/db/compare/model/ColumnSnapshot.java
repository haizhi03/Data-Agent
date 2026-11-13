package edu.zsc.ai.domain.service.db.compare.model;

/** 一列的结构快照，与方言无关。 */
public record ColumnSnapshot(
        String name,
        int ordinalPosition,
        String typeName,
        int columnSize,
        int decimalDigits,
        boolean nullable,
        String defaultValue,
        String comment,
        boolean autoIncrement,
        boolean primaryKeyPart
) {
}
