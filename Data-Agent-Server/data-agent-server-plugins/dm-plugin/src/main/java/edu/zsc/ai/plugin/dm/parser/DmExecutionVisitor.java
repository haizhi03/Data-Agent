package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.dm.parser.base.DMParser;
import edu.zsc.ai.plugin.dm.parser.base.DMParserBaseVisitor;
import org.antlr.v4.runtime.ParserRuleContext;

final class DmExecutionVisitor extends DMParserBaseVisitor<String> {

    private final String source;

    DmExecutionVisitor(String source) {
        this.source = source;
    }

    @Override
    public String visitExplain_statement(DMParser.Explain_statementContext context) {
        ParserRuleContext inner = context.select_statement();
        if (inner == null) inner = context.update_statement();
        if (inner == null) inner = context.delete_statement();
        if (inner == null) inner = context.insert_statement();
        if (inner == null) inner = context.merge_statement();
        if (inner == null) throw new IllegalArgumentException("Unsupported DM EXPLAIN statement");
        return DmSqlParser.sourceSlice(source, inner.getStart().getStartIndex(), inner.getStop().getStopIndex() + 1);
    }
}
