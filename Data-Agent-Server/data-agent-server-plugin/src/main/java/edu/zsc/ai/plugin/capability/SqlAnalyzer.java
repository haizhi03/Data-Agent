package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.model.sql.SqlScriptAnalysis;

public interface SqlAnalyzer {

    SqlScriptAnalysis analyze(String sql);
}
