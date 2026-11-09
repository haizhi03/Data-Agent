package edu.zsc.ai.domain.service.db.sqlgen;

import java.util.List;

/**
 * Pre-quoted inputs for a SQL statement template: identifier quoting is resolved
 * by the caller so strategies stay dialect-agnostic.
 */
public record SqlTemplateContext(
        String tableName,
        List<String> columns,
        List<String> primaryKeyColumns) {
}
