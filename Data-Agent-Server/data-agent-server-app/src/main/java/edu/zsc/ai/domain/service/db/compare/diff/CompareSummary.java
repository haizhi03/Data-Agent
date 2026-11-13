package edu.zsc.ai.domain.service.db.compare.diff;

/** 按表统计的五态数量。 */
public record CompareSummary(int onlySource, int onlyTarget, int different, int same, int readError) {
}
