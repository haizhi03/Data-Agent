package edu.zsc.ai.domain.service.db.compare.model;

import java.util.List;

/** 一个库/模式的完整结构快照。 */
public record SchemaSnapshot(String catalog, String schema, List<TableSnapshot> tables) {
}
