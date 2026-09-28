package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.dm.parser.base.DMParser;
import edu.zsc.ai.plugin.dm.parser.base.DMParserBaseVisitor;
import edu.zsc.ai.plugin.model.sql.SqlType;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;


final class DmStatementVisitor extends DMParserBaseVisitor<DmStatement> {

    private final String source;
    private final CommonTokenStream tokens;

    DmStatementVisitor(String source, CommonTokenStream tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    @Override
    public DmStatement visitUnit_statement(DMParser.Unit_statementContext context) {
        Token start = context.getStart();
        Token stop = context.getStop();
        Token first = DmSqlParser.leadingComment(tokens, start);
        String sql = DmSqlParser.sourceSlice(source, first.getStartIndex(), stop.getStopIndex() + 1);
        SqlType type = new DmSimpleParserVisitor().visit(context);
        DmMetadataVisitor metadata = new DmMetadataVisitor();
        metadata.visit(context);
        boolean readOnly = type == SqlType.SELECT
                && context.data_manipulation_language_statements() != null
                && !hasContext(context, DMParser.Into_clauseContext.class)
                && !hasContext(context, DMParser.For_update_clauseContext.class)
                && !hasContext(context, DMParser.Function_bodyContext.class)
                && !hasContext(context, DMParser.Procedure_bodyContext.class)
                && !metadata.potentiallyMutatingExpression();
        String executableSql = sql;
        if (type == SqlType.EXPLAIN) {
            DMParser.Explain_statementContext explain = context.data_manipulation_language_statements().explain_statement();
            executableSql = new DmExecutionVisitor(source).visit(explain);
        }
        return new DmStatement(sql, type, objectType(context), DmSqlParser.line(source, first.getStartIndex()),
                DmSqlParser.column(source, first.getStartIndex()),
                DmSqlParser.line(source, stop.getStopIndex() + 1),
                DmSqlParser.column(source, stop.getStopIndex() + 1),
                DmSqlParser.utf16Offset(source, first.getStartIndex()),
                DmSqlParser.utf16Offset(source, stop.getStopIndex() + 1),
                metadata.tables(), metadata.columns(), metadata.aliases(), readOnly, executableSql);
    }

    private String objectType(DMParser.Unit_statementContext context) {
        if (context.create_table() != null || context.alter_table() != null
                || context.drop_table() != null) return "TABLE";
        if (context.create_view() != null || context.alter_view() != null
                || context.drop_view() != null) return "VIEW";
        if (context.create_sequence() != null || context.alter_sequence() != null
                || context.drop_sequence() != null) return "SEQUENCE";
        if (context.create_function_body() != null || context.drop_function() != null) return "FUNCTION";
        if (context.create_procedure_body() != null || context.drop_procedure() != null) return "PROCEDURE";
        if (context.create_trigger() != null || context.drop_trigger() != null) return "TRIGGER";
        return null;
    }

    private boolean hasContext(ParseTree tree, Class<? extends ParseTree> kind) {
        if (kind.isInstance(tree)) return true;
        for (int index = 0; index < tree.getChildCount(); index++) {
            if (hasContext(tree.getChild(index), kind)) return true;
        }
        return false;
    }

}
