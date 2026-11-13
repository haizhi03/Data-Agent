package edu.zsc.ai.domain.service.db.compare.dialect;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 达梦规则：字典中的未加引号标识符以大写存储，类型名也习惯大写返回。
 */
@Component
public class DmDialectRules implements DialectRules {

    @Override
    public String dbType() {
        return "dm";
    }

    @Override
    public String normalizeIdentifier(String name) {
        return name == null ? null : name.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public String normalizeTypeName(String typeName) {
        return typeName == null ? null : typeName.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    @Override
    public String normalizeDefault(String rawDefault) {
        if (rawDefault == null) return null;
        String value = rawDefault.trim();
        if (value.isEmpty()) return null;
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.isEmpty() ? null : value;
    }

    @Override
    public String normalizeComment(String comment) {
        if (comment == null) return null;
        String value = comment.trim();
        return value.isEmpty() ? null : value;
    }
}
