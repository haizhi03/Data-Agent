package edu.zsc.ai.domain.service.db.compare.diff;

import java.util.List;

/** 一次结构比较的完整结果。 */
public record SchemaCompareResult(CompareSummary summary, List<TableDiff> tables) {
}
