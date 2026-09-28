package edu.zsc.ai.plugin.dm.executor;

import edu.zsc.ai.plugin.dm.value.DmValueProcessor;
import edu.zsc.ai.plugin.dm.parser.DmSqlParser;
import edu.zsc.ai.plugin.dm.parser.DmStatement;
import edu.zsc.ai.plugin.model.command.sql.AbstractSqlExecutor;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandSubResult;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
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

    /** DM vendor error code for "请求执行超时" (verified against DM8 8.1.3.140). */
    static final int DM_ERROR_CODE_TIMEOUT = -608;

    /**
     * DM vendor error code for "操作被取消" (sqlState 25000), raised on the executing
     * thread after Statement.cancel() stops an in-flight statement; the connection stays
     * usable. Live-verified against DM8 8.1.4.80 / DmJdbcDriver18 8.1.3.140 (see
     * docs/certification/dm8/M1-timeout-cancellation.md).
     */
    static final int DM_ERROR_CODE_CANCELLED = -6515;

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
        result.setTransactionOutcome(TransactionOutcome.NONE);
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
            result.setStatementState(StatementExecutionState.EXECUTED);
        } catch (IllegalArgumentException | SQLException exception) {
            result.setSuccess(false);
            result.setErrorMessage(exception.getMessage());
            // parse failures never reached the DB; SQL exceptions are factual execution failures
            result.setStatementState(exception instanceof SQLException sqlException
                    ? classifyFailure(sqlException)
                    : StatementExecutionState.NOT_EXECUTED);
            if (exception instanceof SQLException sqlException) {
                result.setErrorCode(sqlException.getErrorCode());
                result.setSqlState(sqlException.getSQLState());
                result.setErrorDetail(sqlException.toString());
            }
        } finally {
            long finished = System.currentTimeMillis();
            result.setEndTime(finished);
            result.setExecutionMs(finished - started);
            result.setExecutionTime(finished - started);
        }
        return result;
    }

    @Override
    protected boolean isTimeoutException(SQLException e) {
        return e.getErrorCode() == DM_ERROR_CODE_TIMEOUT || super.isTimeoutException(e);
    }

    /**
     * DM confirms a cancelled statement with vendor code -6515. Only this explicit
     * confirmation may yield CANCELLED — any other error after a cancel request keeps
     * the statement FAILED/UNKNOWN per the base classification.
     */
    @Override
    protected boolean isCancellationException(SQLException e) {
        return e.getErrorCode() == DM_ERROR_CODE_CANCELLED || super.isCancellationException(e);
    }

    @Override
    protected Object getJdbcValue(JdbcValueContext context) throws SQLException {
        return VALUE_PROCESSOR.getJdbcValue(context);
    }

    /** EXPLAIN-branch failure classification, aligned with the base executor ordering. */
    private StatementExecutionState classifyFailure(SQLException e) {
        if (isCancellationException(e)) {
            return StatementExecutionState.CANCELLED;
        }
        if (isTimeoutException(e)) {
            return StatementExecutionState.TIMED_OUT;
        }
        return StatementExecutionState.FAILED;
    }
}
