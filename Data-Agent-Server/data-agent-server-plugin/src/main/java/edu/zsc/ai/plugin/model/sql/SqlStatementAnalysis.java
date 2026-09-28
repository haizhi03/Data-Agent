package edu.zsc.ai.plugin.model.sql;

import java.util.List;
import java.util.Map;

public record SqlStatementAnalysis(String sql, SqlType type, String objectType, int line, int column,
                                   int endLine, int endColumn, int startOffset, int endOffset,
                                   List<String> tables, List<String> columns, Map<String, String> aliases,
                                   boolean readOnly, String executableSql) {
}
