package edu.zsc.ai.domain.service.db.compare.diff;

import java.util.List;

/** 一张表的比较结果，objects 为该表内部的差异项。 */
public record TableDiff(
        String tableName,
        DiffStatus status,
        List<ObjectDiff> objects,
        String message
) {
}
