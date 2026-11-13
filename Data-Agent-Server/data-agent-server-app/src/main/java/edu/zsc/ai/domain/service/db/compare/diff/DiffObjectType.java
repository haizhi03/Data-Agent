package edu.zsc.ai.domain.service.db.compare.diff;

/** 参与比较的对象种类，同时也是比较器的分发键。 */
public enum DiffObjectType {
    TABLE,
    COLUMN,
    PRIMARY_KEY,
    INDEX,
    FOREIGN_KEY,
    UNIQUE,
    CHECK,
    COMMENT
}
