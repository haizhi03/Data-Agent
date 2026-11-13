package edu.zsc.ai.domain.service.db.compare.diff;

/** 单个属性的两端取值，任一端不存在时为 null。 */
public record AttributeDiff(String name, String sourceValue, String targetValue) {
}
