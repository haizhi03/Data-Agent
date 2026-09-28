package edu.zsc.ai.plugin.model.sql;

import java.util.List;

public record SqlScriptAnalysis(List<SqlStatementAnalysis> statements, List<SqlError> errors) {
}
