package edu.zsc.ai.domain.service.db.compare.dialect;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 按数据库类型索引方言规则，未命中时回退到 {@link GenericDialectRules}。 */
@Component
public class DialectRulesRegistry {

    private final Map<String, DialectRules> rules;
    private final DialectRules fallback;

    public DialectRulesRegistry(List<DialectRules> ruleList) {
        Map<String, DialectRules> map = new HashMap<>();
        DialectRules generic = null;
        for (DialectRules rule : ruleList) {
            if ("generic".equalsIgnoreCase(rule.dbType())) {
                generic = rule;
            } else {
                map.put(normalize(rule.dbType()), rule);
            }
        }
        this.rules = Map.copyOf(map);
        this.fallback = generic;
    }

    public DialectRules resolve(String dbType) {
        DialectRules rule = rules.get(normalize(dbType));
        if (rule != null) return rule;
        if (fallback != null) return fallback;
        throw new IllegalStateException("No dialect rules registered");
    }

    private static String normalize(String dbType) {
        return dbType == null ? "" : dbType.trim().toLowerCase(Locale.ROOT);
    }
}
