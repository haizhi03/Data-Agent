package edu.zsc.ai.domain.service.db.compare.model;

import java.util.List;

/** 一个索引的结构快照，columns 保持声明顺序。 */
public record IndexSnapshot(
        String name,
        String type,
        List<String> columns,
        boolean unique,
        boolean primaryKey
) {
}
