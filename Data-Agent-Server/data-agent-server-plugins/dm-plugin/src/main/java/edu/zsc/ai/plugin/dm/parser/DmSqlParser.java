package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.dm.parser.base.DMLexer;
import edu.zsc.ai.plugin.dm.parser.base.DMParser;
import edu.zsc.ai.plugin.model.sql.SqlError;
import edu.zsc.ai.plugin.model.sql.SqlType;
import edu.zsc.ai.plugin.model.sql.SqlValidationResult;
import edu.zsc.ai.plugin.model.sql.SqlScriptAnalysis;
import edu.zsc.ai.plugin.model.sql.SqlStatementAnalysis;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;

public final class DmSqlParser {

    public static final DmSqlParser INSTANCE = new DmSqlParser();

    private DmSqlParser() {
    }

    public ParseResult parserStatements(String sql) {
        return parse(sql, false);
    }

    private ParseResult parse(String sql, boolean simple) {
        if (sql == null || sql.isBlank()) {
            return new ParseResult(List.of(), List.of(new SqlError(1, 0, "SQL statement is empty")));
        }
        ErrorCollector errors = new ErrorCollector(sql);
        DMLexer lexer = new DMLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DMParser parser = new DMParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        DMParser.Sql_scriptContext script = parser.sql_script();
        if (!errors.errors.isEmpty()) {
            return new ParseResult(List.of(), List.copyOf(errors.errors));
        }
        List<DmStatement> statements;
        if (simple) {
            DmSimpleParserVisitor visitor = new DmSimpleParserVisitor();
            statements = script.unit_statement().stream().map(context -> {
                Token start = context.getStart();
                Token stop = context.getStop();
                Token first = leadingComment(tokens, start);
                String text = sourceSlice(sql, first.getStartIndex(), stop.getStopIndex() + 1);
                return new DmStatement(text, visitor.visit(context), null,
                        line(sql, first.getStartIndex()), column(sql, first.getStartIndex()),
                        line(sql, stop.getStopIndex() + 1), column(sql, stop.getStopIndex() + 1),
                        utf16Offset(sql, first.getStartIndex()), utf16Offset(sql, stop.getStopIndex() + 1),
                        List.of(), List.of(), java.util.Map.of(), false, text);
            }).toList();
        } else {
            DmStatementVisitor visitor = new DmStatementVisitor(sql, tokens);
            statements = script.unit_statement().stream().map(visitor::visit).toList();
        }
        return new ParseResult(statements, List.of());
    }

    public ParseResult simpleParserStatements(String sql) {
        return parse(sql, true);
    }

    public ParseResult validTableStatements(String sql) {
        return parserStatements(sql);
    }

    public ParseResult parserSqlScript(String sql) {
        return parse(sql, true);
    }

    public SqlScriptAnalysis analyze(String sql) {
        ParseResult result = parserStatements(sql);
        List<SqlStatementAnalysis> statements = result.statements().stream()
                .map(statement -> new SqlStatementAnalysis(statement.sql(), statement.type(), statement.objectType(),
                        statement.line(), statement.column(), statement.endLine(), statement.endColumn(),
                        statement.startOffset(), statement.endOffset(), statement.tables(), statement.columns(),
                        statement.aliases(), statement.readOnly(), statement.executableSql()))
                .toList();
        return new SqlScriptAnalysis(statements, result.errors());
    }

    public boolean isSelect(String sql) {
        ParseResult result = parserStatements(sql);
        return result.errors().isEmpty() && result.statements().size() == 1
                && result.statements().get(0).readOnly();
    }

    public SqlValidationResult validate(String sql) {
        ParseResult result = parserStatements(sql);
        if (!result.errors().isEmpty()) {
            return SqlValidationResult.invalid(SqlType.UNKNOWN, result.errors());
        }
        if (result.statements().size() != 1) {
            return SqlValidationResult.invalid(SqlType.UNKNOWN,
                    List.of(new SqlError(1, 0, "Expected exactly one SQL statement")));
        }
        DmStatement statement = result.statements().get(0);
        SqlType type = statement.type() == SqlType.SELECT && !statement.readOnly()
                ? SqlType.UNKNOWN : statement.type();
        return SqlValidationResult.valid(type, statement.tables(), statement.columns());
    }

    public SqlType classifySql(String sql) {
        return validate(sql).sqlType();
    }

    public List<String> split(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        ParseResult result = parserSqlScript(sql);
        if (!result.errors().isEmpty()) {
            return List.of(sql);
        }
        return result.statements().stream().map(DmStatement::sql).toList();
    }

    public List<Token> getAllTokens(String sql) {
        DMLexer lexer = new DMLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return List.copyOf(tokens.getTokens());
    }

    public DmStatement parseExecutableSql(String sql) {
        ParseResult result = parserStatements(sql);
        if (!result.errors().isEmpty() || result.statements().size() != 1) {
            throw new IllegalArgumentException("Invalid DM SQL: " + result.errors());
        }
        return result.statements().get(0);
    }

    public boolean startsWithExplain(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        DMLexer lexer = new DMLexer(CharStreams.fromString(sql));
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                return token.getType() == DMLexer.EXPLAIN;
            }
        }
        return false;
    }

    public record ParseResult(List<DmStatement> statements, List<SqlError> errors) {
    }

    static String sourceSlice(String source, int start, int endExclusive) {
        return source.substring(utf16Offset(source, start), utf16Offset(source, endExclusive));
    }

    static int utf16Offset(String source, int codePointOffset) {
        return source.offsetByCodePoints(0, codePointOffset);
    }

    static int column(String source, int codePointOffset) {
        int end = utf16Offset(source, codePointOffset);
        return end - source.lastIndexOf('\n', end - 1) - 1;
    }

    static int line(String source, int codePointOffset) {
        int end = utf16Offset(source, codePointOffset);
        return (int) source.substring(0, end).chars().filter(character -> character == '\n').count() + 1;
    }

    static Token leadingComment(CommonTokenStream tokens, Token start) {
        List<Token> hidden = tokens.getHiddenTokensToLeft(start.getTokenIndex());
        if (hidden != null) {
            for (Token token : hidden) {
                if (token.getType() == DMLexer.SINGLE_LINE_COMMENT
                        || token.getType() == DMLexer.MULTI_LINE_COMMENT
                        || token.getType() == DMLexer.REMARK_COMMENT) {
                    return token;
                }
            }
        }
        return start;
    }

    private static final class ErrorCollector extends BaseErrorListener {

        private final List<SqlError> errors = new ArrayList<>();
        private final String source;

        private ErrorCollector(String source) {
            this.source = source;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String message, RecognitionException exception) {
            int column = offendingSymbol instanceof Token token && token.getStartIndex() >= 0
                    ? column(source, token.getStartIndex()) : charPositionInLine;
            errors.add(new SqlError(line, column, message));
        }
    }
}
