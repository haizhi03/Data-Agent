package edu.zsc.ai.plugin.dm.executor;

import edu.zsc.ai.plugin.dm.value.DmValueProcessor;
import edu.zsc.ai.plugin.dm.parser.DmSqlParser;
import edu.zsc.ai.plugin.dm.parser.DmStatement;
import edu.zsc.ai.plugin.model.command.sql.AbstractSqlExecutor;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandSubResult;
import edu.zsc.ai.plugin.value.JdbcValueContext;
import edu.zsc.ai.plugin.value.ValueProcessor;

import java.sql.SQLException;
import java.util.List;

/**
 * DM (DaMeng) specific SQL executor that handles DM data type conversions properly.
 * Uses {@link DmValueProcessor} for type-specific value extraction.
 *
 * <p>Handles special types like TIMESTAMP WITH TIME ZONE, BLOB/CLOB/BFILE, BIT,
 * NUMBER precision, and DM's empty-string-as-NULL semantics through the
 * factory-based value processor system.
 *
 * @author hhz
 */
public class DmSqlExecutor extends AbstractSqlExecutor {

    private static final ValueProcessor VALUE_PROCESSOR = DmValueProcessor.INSTANCE;
    private final DmExplainClient explainClient = new DmExplainClient();

    @Override
    public SqlCommandResult executeCommand(SqlCommandRequest command) {
        if (!DmSqlParser.INSTANCE.startsWithExplain(command.getExecuteSql())) {
            return super.executeCommand(command);
        }
        SqlCommandResult result = new SqlCommandResult();
        result.setOriginalSql(command.getOriginalSql());
        result.setExecutedSql(command.getExecuteSql());
        long started = System.currentTimeMillis();
        result.setStartTime(started);
        try {
            DmStatement statement = DmSqlParser.INSTANCE.parseExecutableSql(command.getExecuteSql());
            if (statement.type() != edu.zsc.ai.plugin.model.sql.SqlType.EXPLAIN) {
                throw new IllegalArgumentException("Expected a single DM EXPLAIN statement");
            }
            String plan = explainClient.getExplainInfo(command.getConnection(), statement.executableSql());
            if (plan == null) {
                throw new SQLException("DM explain returned no plan");
            }
            SqlCommandSubResult subResult = new SqlCommandSubResult();
            subResult.setQuery(true);
            subResult.setHeaders(List.of("Execution Plan"));
            subResult.setRows(List.of(List.of(plan)));
            subResult.setFetchRows(1);
            result.setResults(List.of(subResult));
            result.setQuery(true);
            result.setHeaders(subResult.getHeaders());
            result.setRows(subResult.getRows());
            result.setFetchRows(1);
            result.setSuccess(true);
        } catch (IllegalArgumentException | SQLException exception) {
            result.setSuccess(false);
            result.setErrorMessage(exception.getMessage());
        } finally {
            long finished = System.currentTimeMillis();
            result.setEndTime(finished);
            result.setExecutionMs(finished - started);
            result.setExecutionTime(finished - started);
        }
        return result;
    }

    @Override
    protected Object getJdbcValue(JdbcValueContext context) throws SQLException {
        return VALUE_PROCESSOR.getJdbcValue(context);
    }
}
