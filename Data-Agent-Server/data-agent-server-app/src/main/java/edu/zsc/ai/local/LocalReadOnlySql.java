package edu.zsc.ai.local;

import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

/** Restricted query subset; a SELECT/WITH prefix and a JDBC hint alone are insufficient. */
final class LocalReadOnlySql {
    private static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "CREATE", "ALTER", "DROP",
            "TRUNCATE", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "INTO", "OUTFILE", "DUMPFILE", "LOCK", "UNLOCK",
            "NEXTVAL", "CURRVAL", "SETVAL", "FOR", "PROCEDURE", "COMMIT", "ROLLBACK", "SET", "USE");
    private static final Set<String> CALLS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NULLIF", "NVL", "NVL2",
            "LOWER", "UPPER", "TRIM", "LTRIM", "RTRIM", "CONCAT", "LENGTH", "CHAR_LENGTH", "SUBSTR", "SUBSTRING", "ROUND",
            "TRUNC", "ABS", "MOD", "CEIL", "CEILING", "FLOOR", "CAST", "CONVERT", "VARCHAR", "CHAR", "DECIMAL", "NUMERIC",
            "TO_CHAR", "TO_DATE", "TO_NUMBER", "EXTRACT", "DATE_FORMAT", "IFNULL", "ISNULL", "IF", "GREATEST", "LEAST",
            "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "IN", "EXISTS", "OVER", "AS", "FROM", "JOIN", "ON", "AND", "OR", "NOT",
            "WHERE", "HAVING", "WHEN", "SELECT", "DATABASE", "SCHEMA", "VERSION", "USER", "CURRENT_USER");
    static void validate(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 262144) throw LocalRequestException.invalid();
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
            if (statements.isEmpty() || statements.size() > 100 || statements.stream().anyMatch(s -> !(s instanceof Select))) throw unsupported();
        } catch (LocalRequestException e) { throw e; } catch (Exception e) { throw unsupported(); }
        var tokens = tokens(sql);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (FORBIDDEN.contains(token) || token.equals("@") || token.equals(":")) throw unsupported();
            if (i + 1 < tokens.size() && tokens.get(i + 1).equals("(")
                    && (token.equals("<QUOTED>") || Character.isUnicodeIdentifierStart(token.codePointAt(0)) || token.startsWith("$"))) {
                if (!CALLS.contains(token) || (i > 0 && tokens.get(i - 1).equals("."))) throw unsupported();
            }
        }
    }
    static List<String> tokens(String sql) {
        var result = new ArrayList<String>();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2); i = end < 0 ? sql.length() : end + 1; continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                if (i + 2 < sql.length() && (sql.charAt(i + 2) == '!' || sql.charAt(i + 2) == '+')) throw unsupported();
                int end = sql.indexOf("*/", i + 2); if (end < 0) throw unsupported(); i = end + 2; continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c; boolean closed = false; int start = ++i;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == '\\') throw unsupported();
                    if (next == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw unsupported();
                if (quote != '\'' && Set.of("NEXTVAL", "CURRVAL", "SETVAL").contains(sql.substring(start, i - 1).toUpperCase(Locale.ROOT))) throw unsupported();
                result.add(quote == '\'' ? "<STRING>" : "<QUOTED>"); continue;
            }
            int codePoint = sql.codePointAt(i);
            if (Character.isUnicodeIdentifierStart(codePoint) || codePoint == '$') {
                int start = i; i += Character.charCount(codePoint);
                while (i < sql.length() && (Character.isUnicodeIdentifierPart(sql.codePointAt(i)) || sql.charAt(i) == '$')) {
                    i += Character.charCount(sql.codePointAt(i));
                }
                result.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else { result.add(String.valueOf(c)); i++; }
        }
        return result;
    }
    static LocalRequestException unsupported() {
        return new LocalRequestException(409, "LOCAL_FEATURE_NOT_SUPPORTED", "当前本地工作台仅支持只读查询；此语句不在支持范围内");
    }
}
