package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.dialect.DialectRules;

/** 一次比较的上下文：方言规则 + 用户选项。 */
public record CompareContext(DialectRules rules, boolean ignoreColumnOrder, boolean ignoreComments) {
}
