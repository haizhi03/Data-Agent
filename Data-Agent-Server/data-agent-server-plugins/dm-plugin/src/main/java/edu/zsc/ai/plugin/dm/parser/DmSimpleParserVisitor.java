package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.dm.parser.base.DMLexer;
import edu.zsc.ai.plugin.dm.parser.base.DMParser;
import edu.zsc.ai.plugin.dm.parser.base.DMParserBaseVisitor;
import edu.zsc.ai.plugin.model.sql.SqlType;

final class DmSimpleParserVisitor extends DMParserBaseVisitor<SqlType> {

    @Override
    public SqlType visitUnit_statement(DMParser.Unit_statementContext context) {
        DMParser.Data_manipulation_language_statementsContext dml = context.data_manipulation_language_statements();
        if (dml != null) {
            if (dml.select_statement() != null) return SqlType.SELECT;
            if (dml.insert_statement() != null) return SqlType.INSERT;
            if (dml.update_statement() != null) return SqlType.UPDATE;
            if (dml.delete_statement() != null) return SqlType.DELETE;
            if (dml.merge_statement() != null) return SqlType.MERGE;
            if (dml.explain_statement() != null) return SqlType.EXPLAIN;
        }
        return switch (context.getStart().getType()) {
            case DMLexer.CREATE -> SqlType.CREATE;
            case DMLexer.ALTER -> SqlType.ALTER;
            case DMLexer.DROP -> SqlType.DROP;
            case DMLexer.GRANT -> SqlType.GRANT;
            case DMLexer.REVOKE -> SqlType.REVOKE;
            case DMLexer.COMMIT -> SqlType.COMMIT;
            case DMLexer.ROLLBACK -> SqlType.ROLLBACK;
            default -> SqlType.UNKNOWN;
        };
    }
}
