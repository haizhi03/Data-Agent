package edu.zsc.ai.domain.service.db.compare.diff;

import java.util.List;

/** 一个被比较对象（列、索引、约束等）的结果。 */
public record ObjectDiff(
        DiffObjectType objectType,
        String objectKey,
        DiffStatus status,
        List<AttributeDiff> attributes
) {
}
