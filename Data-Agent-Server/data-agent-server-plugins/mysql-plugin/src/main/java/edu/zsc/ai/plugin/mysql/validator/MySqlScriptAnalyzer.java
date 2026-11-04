package edu.zsc.ai.plugin.mysql.validator;

import edu.zsc.ai.plugin.capability.SqlAnalyzer;
import edu.zsc.ai.plugin.capability.SqlSplitter;
import edu.zsc.ai.plugin.model.sql.SqlError;
import edu.zsc.ai.plugin.model.sql.SqlScriptAnalysis;
import edu.zsc.ai.plugin.model.sql.SqlStatementAnalysis;
import edu.zsc.ai.plugin.mysql.parser.MySqlLexer;
import edu.zsc.ai.plugin.mysql.parser.MySqlParser;
import org.antlr.v4.runtime.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shares parser boundaries between cursor execution and batch execution. */
public final class MySqlScriptAnalyzer implements SqlAnalyzer, SqlSplitter {
    public static final MySqlScriptAnalyzer INSTANCE = new MySqlScriptAnalyzer();

    @Override
    public SqlScriptAnalysis analyze(String sql) {
        if (sql == null || sql.isBlank()) return new SqlScriptAnalysis(List.of(), List.of());
        List<SqlError> errors = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line, int column,
                                    String message, RecognitionException exception) {
                errors.add(new SqlError(line, column, message));
            }
        };
        MySqlLexer lexer = new MySqlLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        MySqlParser.RootContext root = parser.root();
        if (!errors.isEmpty()) return new SqlScriptAnalysis(List.of(), List.copyOf(errors));
        if (root.sqlStatements() == null) return new SqlScriptAnalysis(List.of(), List.of());

        List<SqlStatementAnalysis> statements = new ArrayList<>();
        for (MySqlParser.SqlStatementContext context : root.sqlStatements().sqlStatement()) {
            Token start = context.getStart();
            Token stop = context.getStop();
            // ANTLR uses code-point offsets; the editor and Java strings use UTF-16.
            int from = sql.offsetByCodePoints(0, start.getStartIndex());
            int to = sql.offsetByCodePoints(0, stop.getStopIndex() + 1);
            String text = sql.substring(from, to);
            MySqlAstExtractor extractor = new MySqlAstExtractor();
            extractor.visit(context);
            // A routine body also contains DML; classify the outer statement.
            var type = new MySqlSqlValidator().classifySql(text);
            String tail = stop.getText();
            int endLine = stop.getLine() + (int) tail.chars().filter(c -> c == '\n').count();
            int endColumn = tail.contains("\n") ? tail.length() - tail.lastIndexOf('\n') - 1
                    : stop.getCharPositionInLine() + tail.length();
            statements.add(new SqlStatementAnalysis(text, type, null,
                    start.getLine(), start.getCharPositionInLine(), endLine, endColumn, from, to,
                    extractor.getTables(), extractor.getColumns(), Map.of(), type.isReadOnly(), text));
        }
        return new SqlScriptAnalysis(List.copyOf(statements), List.of());
    }

    @Override
    public List<String> split(String sql) {
        SqlScriptAnalysis analysis = analyze(sql);
        // Preserve unsupported SQL as one unit; never execute a partially parsed body.
        if (!analysis.errors().isEmpty()) return List.of(sql);
        return analysis.statements().stream().map(SqlStatementAnalysis::sql).toList();
    }
}
