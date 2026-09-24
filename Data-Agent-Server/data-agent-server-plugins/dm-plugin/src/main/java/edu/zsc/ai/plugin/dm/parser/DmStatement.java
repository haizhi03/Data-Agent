package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.model.sql.SqlType;

import java.util.List;
import java.util.Map;

public record DmStatement(String sql, SqlType type, String objectType, int line, int column,
                          int endLine, int endColumn, int startOffset, int endOffset,
                          List<String> tables, List<String> columns, Map<String, String> aliases,
                          boolean readOnly, String executableSql) {
}
