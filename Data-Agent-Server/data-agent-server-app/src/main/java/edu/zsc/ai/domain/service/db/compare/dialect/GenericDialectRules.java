package edu.zsc.ai.domain.service.db.compare.dialect;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 通用规则：忽略大小写，去掉默认值外层引号。MySQL、达梦等未注册方言时的兜底实现。
 */
@Component
public class GenericDialectRules implements DialectRules {

    @Override
    public String dbType() {
        return "generic";
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
        // MySQL 会把字符串默认值带引号返回，去掉外层引号后才能与另一端等价比较。
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
