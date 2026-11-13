package edu.zsc.ai.domain.service.db.compare.model;

import java.util.List;

/**
 * 一张表的结构快照。列读取失败时 columnError 非空，该表整体标记为 READ_ERROR；
 * 索引或约束读取失败只记在 auxiliaryError 中，不影响列的比对。
 */
public record TableSnapshot(
        String name,
        String comment,
        List<ColumnSnapshot> columns,
        List<IndexSnapshot> indexes,
        List<ConstraintSnapshot> constraints,
        String columnError,
        String auxiliaryError
) {

    public boolean columnsReadable() {
        return columnError == null || columnError.isBlank();
    }
}
