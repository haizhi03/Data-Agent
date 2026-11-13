package edu.zsc.ai.domain.service.db.compare.model;

import java.util.List;

/** 一个约束的结构快照，columns 保持声明顺序。 */
public record ConstraintSnapshot(
        String name,
        String type,
        List<String> columns,
        String referencedSchema,
        String referencedTable,
        List<String> referencedColumns,
        String checkExpression,
        boolean enabled
) {
}
