package edu.zsc.ai.plugin.model.command.sql;

import edu.zsc.ai.plugin.capability.CommandExecutor;
import edu.zsc.ai.plugin.execution.StatementCancellationRegistry;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import edu.zsc.ai.plugin.value.JdbcValueContext;
import edu.zsc.ai.plugin.value.JdbcValueContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract SQL executor that provides common SQL execution logic.
 * Uses ValueProcessor for database-specific type conversions.
 *
 *
 * <p><b>Note:</b> This executor does NOT close the connection. The caller is
 * responsible for managing the connection lifecycle.
 *
 * <p><b>Exit semantics:</b> every exit path explicitly fills
 * {@link SqlCommandResult#getStatementState()} and
 * {@link SqlCommandResult#getTransactionOutcome()}:
 * <ul>
 *   <li>success with transaction → COMMITTED; without transaction → EXECUTED</li>
 *   <li>execute-phase SQLException → FAILED; TIMED_OUT via {@link #isTimeoutException(SQLException)};
 *       CANCELLED only via {@link #isCancellationException(SQLException)} (vendor-confirmed cancel);
 *       UNKNOWN via {@link #isConnectionLossException(SQLException)} (connection lost, outcome
 *       undecidable — no data-state claim is made and no rollback is asserted);
 *       transaction outcome reflects the actual rollback result</li>
 *   <li>commit() failure → statementState UNKNOWN, transactionOutcome UNKNOWN; a subsequent
 *       successful rollback may downgrade both to ROLLED_BACK, a failed rollback keeps UNKNOWN</li>
 * </ul>
 * No exception is swallowed into a success result.
 *
 * @author Data-Agent Team
 */
public abstract class AbstractSqlExecutor implements CommandExecutor<SqlCommandRequest, SqlCommandResult> {

    private static final Logger log = LoggerFactory.getLogger(AbstractSqlExecutor.class);

    /**
     * Default per-result-set row cap guarding against unbounded memory growth on
     * large result sets (M1-09). Overridable per request via
     * {@link SqlCommandRequest#getMaxRows()} (a value &lt;= 0 disables the cap).
     */
    protected static final int DEFAULT_MAX_ROWS = 10_000;

    protected abstract Object getJdbcValue(JdbcValueContext context) throws SQLException;

    protected boolean getOriginalAutoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        } catch (SQLException e) {
            log.warn("Failed to get original autoCommit: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public SqlCommandResult executeCommand(final SqlCommandRequest command) {
        Connection connection = command.getConnection();
        SqlCommandResult result = initResult(command);
        boolean originalAutoCommit = getOriginalAutoCommit(connection);

        boolean submitted = false;
        try {
            disableAutoCommitIfNeeded(connection, command);
            submitted = true;
            executeSqlStatement(connection, command, result);
        } catch (SQLException e) {
            handleSqlException(connection, command, result, e, submitted);
            restoreAutoCommit(connection, command, originalAutoCommit);
            return result;
        }

        try {
            commitTransactionIfNeeded(connection, command);
            markSuccess(command, result);
        } catch (SQLException commitException) {
            handleCommitException(connection, command, result, commitException);
        } finally {
            restoreAutoCommit(connection, command, originalAutoCommit);
        }
        return result;
    }

    /**
     * Create initial result object with basic information
     */
    private SqlCommandResult initResult(SqlCommandRequest command) {
        SqlCommandResult result = new SqlCommandResult();
        result.setSuccess(true);
        result.setOriginalSql(command.getOriginalSql());
        result.setExecutedSql(command.getExecuteSql());
        result.setStatementState(StatementExecutionState.NOT_EXECUTED);
        result.setTransactionOutcome(TransactionOutcome.NONE);
        return result;
    }

    private void markSuccess(SqlCommandRequest command, SqlCommandResult result) {
        if (command.isNeedTransaction()) {
            result.setStatementState(StatementExecutionState.COMMITTED);
            result.setTransactionOutcome(TransactionOutcome.COMMITTED);
        } else {
            result.setStatementState(StatementExecutionState.EXECUTED);
            result.setTransactionOutcome(TransactionOutcome.NONE);
        }
    }

    /**
     * Disable autoCommit if transaction is needed.
     * Subclasses can override this method to customize transaction handling
     * for databases that don't support transactions or have special requirements.
     *
     * @param connection the database connection
     * @param command    the SQL command request
     * @throws SQLException if unable to set autoCommit
     */
    protected void disableAutoCommitIfNeeded(Connection connection, SqlCommandRequest command) throws SQLException {
        if (command.isNeedTransaction()) {
            connection.setAutoCommit(false);
        }
    }

    /**
     * Execute SQL statement and populate result.
     *
     * <p>The statement is registered in {@link StatementCancellationRegistry} under the
     * request's execution id (when set) so a concurrent cancel request can reach it.
     */
    private void executeSqlStatement(Connection connection, SqlCommandRequest command, SqlCommandResult result)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            applyQueryTimeout(statement, command);
            applyFetchSize(statement, command);
            try (StatementCancellationRegistry.Registration registration =
                         StatementCancellationRegistry.getInstance()
                                 .register(command.getExecutionId(), statement)) {
                String sql = command.getExecuteSql();
                long start = System.currentTimeMillis();
                result.setStartTime(start);
                boolean hasResultSet = statement.execute(sql);
                long execEnd = System.currentTimeMillis();
                result.setExecutionMs(execEnd - start);
                List<SqlCommandSubResult> results = new ArrayList<>();
                while (true) {
                    if (hasResultSet) {
                        SqlCommandSubResult sub = new SqlCommandSubResult();
                        sub.setQuery(true);
                        sub.setExecutionMs(result.getExecutionMs());
                        processQueryResult(statement, command, result, sub);
                        results.add(sub);
                    } else {
                        int updateCount = statement.getUpdateCount();
                        if (updateCount == -1) {
                            break;
                        }
                        SqlCommandSubResult sub = new SqlCommandSubResult();
                        sub.setQuery(false);
                        sub.setExecutionMs(result.getExecutionMs());
                        sub.setFetchingMs(0L);
                        processDmlResult(updateCount, sub);
                        results.add(sub);
                    }
                    hasResultSet = statement.getMoreResults();
                }
                result.setResults(results);
                if (!results.isEmpty()) {
                    applyFirstResult(result, results.get(0));
                }
                addWarnings(statement.getWarnings(), result, null);
                long end = System.currentTimeMillis();
                result.setEndTime(end);
                result.setExecutionTime(end - start);
            }
        }
    }

    /**
     * Process DML operation result
     */
    private void processDmlResult(int updateCount, SqlCommandSubResult sub) {
        sub.setAffectedRows(updateCount);
    }

    private void addWarnings(SQLWarning warning, SqlCommandResult result, SqlCommandSubResult sub) {
        SQLWarning current = warning;
        while (current != null) {
            addMessage(result, sub, new SqlMessageInfo(
                    SqlMessageLevel.WARN,
                    String.valueOf(current.getErrorCode()),
                    current.getSQLState(),
                    current.getMessage(),
                    current.toString()
            ));
            current = current.getNextWarning();
        }
    }

    private void addMessage(SqlCommandResult result, SqlCommandSubResult sub, SqlMessageInfo message) {
        if (result.getMessages() == null) {
            result.setMessages(new ArrayList<>());
        }
        result.getMessages().add(message);
        if (sub != null) {
            if (sub.getMessages() == null) {
                sub.setMessages(new ArrayList<>());
            }
            sub.getMessages().add(message);
        }
    }

    /**
     * Commit transaction if needed.
     * Subclasses can override this method to customize commit behavior
     * for databases that don't support transactions.
     *
     * @param connection the database connection
     * @param command    the SQL command request
     * @throws SQLException if unable to commit
     */
    protected void commitTransactionIfNeeded(Connection connection, SqlCommandRequest command) throws SQLException {
        if (command.isNeedTransaction()) {
            connection.commit();
        }
    }

    /**
     * Apply the request timeout (milliseconds) as Statement.setQueryTimeout (seconds, rounded up).
     */
    private void applyQueryTimeout(Statement statement, SqlCommandRequest command) throws SQLException {
        Integer timeoutMs = command.getTimeoutMs();
        if (timeoutMs != null && timeoutMs > 0) {
            statement.setQueryTimeout((int) Math.ceil(timeoutMs / 1000.0));
        }
    }

    /**
     * Apply the request fetch-size hint when provided.
     */
    private void applyFetchSize(Statement statement, SqlCommandRequest command) throws SQLException {
        Integer fetchSize = command.getFetchSize();
        if (fetchSize != null && fetchSize > 0) {
            statement.setFetchSize(fetchSize);
        }
    }

    /**
     * Effective row cap for one result set: the request value wins; null falls back to
     * {@link #DEFAULT_MAX_ROWS}; a value &lt;= 0 means unlimited.
     */
    private int resolveMaxRows(SqlCommandRequest command) {
        Integer maxRows = command.getMaxRows();
        if (maxRows == null) {
            return DEFAULT_MAX_ROWS;
        }
        return maxRows <= 0 ? Integer.MAX_VALUE : maxRows;
    }

    /**
     * Plugin hook: whether the given exception represents an actively cancelled statement.
     * The default is deliberately conservative (false): a vendor may only claim CANCELLED
     * when the driver/server explicitly confirmed the cancel — databases override this
     * hook with their vendor error code (e.g. DM -6515). Checked BEFORE
     * {@link #isTimeoutException(SQLException)}.
     *
     * @param e the SQLException raised during execution
     * @return true only when the exception confirms the statement was cancelled
     */
    protected boolean isCancellationException(SQLException e) {
        return false;
    }

    /**
     * Plugin hook: whether the given exception means the connection was lost mid-flight.
     * Default recognizes SQLState class "08" (connection exception). When the connection
     * is lost the statement outcome is undecidable — the caller must report UNKNOWN, never
     * FAILED/CANCELLED, and must not claim the transaction rolled back.
     *
     * @param e the SQLException raised during execution
     * @return true if the connection was lost
     */
    protected boolean isConnectionLossException(SQLException e) {
        String sqlState = e.getSQLState();
        return sqlState != null && sqlState.startsWith("08");
    }

    /**
     * Plugin hook: whether the given exception represents a statement timeout.
     * Default recognizes SQLTimeoutException, SQLState class "57" (e.g. 57014 query_canceled)
     * and HY008 (operation canceled). Database plugins override with vendor error codes.
     *
     * @param e the SQLException raised during execution
     * @return true if the statement timed out
     */
    protected boolean isTimeoutException(SQLException e) {
        if (e instanceof SQLTimeoutException) {
            return true;
        }
        String sqlState = e.getSQLState();
        return sqlState != null && (sqlState.startsWith("57") || "HY008".equals(sqlState));
    }

    /**
     * Handle SQL exception from the execute phase and rollback the transaction if needed.
     * Statement state, in priority order: NOT_EXECUTED when the statement never reached the
     * DB, CANCELLED only when the cancellation hook confirms the cancel, TIMED_OUT when the
     * timeout hook matches, UNKNOWN when the connection was lost (outcome undecidable),
     * otherwise FAILED. The transaction outcome mirrors the actual rollback result
     * (ROLLED_BACK / UNKNOWN / NONE); on connection loss the rollback attempt is skipped
     * and the outcome stays UNKNOWN.
     */
    private void handleSqlException(Connection connection, SqlCommandRequest command, SqlCommandResult result,
                                    SQLException e, boolean submitted) {
        result.setSuccess(false);
        if (!submitted) {
            result.setStatementState(StatementExecutionState.NOT_EXECUTED);
            result.setTransactionOutcome(rollbackOutcome(connection, command, e));
        } else if (isCancellationException(e)) {
            result.setStatementState(StatementExecutionState.CANCELLED);
            result.setTransactionOutcome(rollbackOutcome(connection, command, e));
        } else if (isTimeoutException(e)) {
            result.setStatementState(StatementExecutionState.TIMED_OUT);
            result.setTransactionOutcome(rollbackOutcome(connection, command, e));
        } else if (isConnectionLossException(e)) {
            // connection gone: neither the statement outcome nor a rollback can be confirmed
            log.warn("Connection lost during statement execution, outcome undecidable: {}", e.getMessage());
            result.setStatementState(StatementExecutionState.UNKNOWN);
            result.setTransactionOutcome(command.isNeedTransaction()
                    ? TransactionOutcome.UNKNOWN : TransactionOutcome.NONE);
        } else {
            result.setStatementState(StatementExecutionState.FAILED);
            result.setTransactionOutcome(rollbackOutcome(connection, command, e));
        }
        fillErrorFields(result, e);
    }

    /**
     * Handle a commit() failure. Per the M1 contract the outcome is UNKNOWN — we must not
     * claim committed or rolled back at this point. A subsequent successful rollback may
     * downgrade both states to ROLLED_BACK; a failed rollback keeps UNKNOWN.
     */
    private void handleCommitException(Connection connection, SqlCommandRequest command, SqlCommandResult result,
                                       SQLException e) {
        result.setSuccess(false);
        result.setStatementState(StatementExecutionState.UNKNOWN);
        result.setTransactionOutcome(TransactionOutcome.UNKNOWN);
        fillErrorFields(result, e);
        if (rollbackTransactionIfNeeded(connection, command, e)) {
            result.setStatementState(StatementExecutionState.ROLLED_BACK);
            result.setTransactionOutcome(TransactionOutcome.ROLLED_BACK);
        }
    }

    private void fillErrorFields(SqlCommandResult result, SQLException e) {
        String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
        result.setErrorMessage(e.getClass().getSimpleName() + ": " + message);
        result.setErrorCode(e.getErrorCode());
        result.setSqlState(e.getSQLState());
        result.setErrorDetail(e.toString());
        addMessage(result, null, new SqlMessageInfo(
                SqlMessageLevel.ERROR,
                String.valueOf(e.getErrorCode()),
                e.getSQLState(),
                message,
                e.toString()
        ));
    }

    private TransactionOutcome rollbackOutcome(Connection connection, SqlCommandRequest command, SQLException e) {
        if (!command.isNeedTransaction()) {
            return TransactionOutcome.NONE;
        }
        return rollbackTransactionIfNeeded(connection, command, e)
                ? TransactionOutcome.ROLLED_BACK
                : TransactionOutcome.UNKNOWN;
    }

    /**
     * Rollback transaction if needed.
     * Subclasses can override this method to customize rollback behavior
     * for databases that don't support transactions.
     *
     * @param connection the database connection
     * @param command    the SQL command request
     * @param e          the original SQLException
     * @return true only when the rollback was actually issued and completed without error;
     *         false when no transaction was used, the connection was unusable, or rollback failed
     */
    protected boolean rollbackTransactionIfNeeded(Connection connection, SqlCommandRequest command, SQLException e) {
        if (!command.isNeedTransaction()) {
            return false;
        }
        try {
            if (connection.isClosed()) {
                log.warn("Cannot roll back: connection is already closed");
                return false;
            }
            connection.rollback();
            return true;
        } catch (SQLException rollbackEx) {
            e.addSuppressed(rollbackEx);
            return false;
        }
    }

    /**
     * Restore original autoCommit state.
     * Subclasses can override this method to customize autoCommit restoration
     * for databases that don't support transactions.
     *
     * @param connection         the database connection
     * @param command            the SQL command request
     * @param originalAutoCommit the original autoCommit state to restore
     */
    protected void restoreAutoCommit(Connection connection, SqlCommandRequest command, boolean originalAutoCommit) {
        if (command.isNeedTransaction()) {
            try {
                if (!connection.isClosed()) {
                    connection.setAutoCommit(originalAutoCommit);
                }
            } catch (SQLException e) {
                log.warn("Failed to restore autoCommit: {}", e.getMessage());
            }
        }
    }

    /**
     * Process query result. Rows are materialized up to the effective cap
     * ({@link SqlCommandRequest#getMaxRows()}, default {@link #DEFAULT_MAX_ROWS}); when the
     * cap is hit the remaining rows are not read and the sub-result is marked
     * truncated/limitApplied. This bounds memory growth on large result sets.
     *
     * @param statement SQL statement
     * @param command   the originating request (row cap source)
     * @param result    result object
     * @throws SQLException SQL exception
     */
    private void processQueryResult(Statement statement, SqlCommandRequest command,
                                    SqlCommandResult result, SqlCommandSubResult sub) throws SQLException {
        List<String> headers = new ArrayList<>();
        List<SqlColumnInfo> columns = new ArrayList<>();

        try (ResultSet resultSet = statement.getResultSet()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            // Get column names (use 1-based index consistently)
            for (int i = 1; i <= columnCount; i++) {
                String name = metaData.getColumnName(i);
                String label = metaData.getColumnLabel(i);
                String header = (label != null && !label.isBlank()) ? label : name;
                headers.add(header);
                SqlColumnInfo columnInfo = new SqlColumnInfo(
                        name,
                        label,
                        metaData.getColumnTypeName(i),
                        metaData.getColumnType(i),
                        metaData.getPrecision(i),
                        metaData.getScale(i),
                        metaData.isNullable(i) == ResultSetMetaData.columnNullable,
                        metaData.getTableName(i)
                );
                columns.add(columnInfo);
            }

            // Get data rows, bounded by the effective row cap
            int maxRows = resolveMaxRows(command);
            long fetchStart = System.currentTimeMillis();
            List<List<Object>> rows = new ArrayList<>();
            while (rows.size() < maxRows && resultSet.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    // Build context from metadata using factory
                    JdbcValueContext context = JdbcValueContextFactory.fromMetaData(resultSet, metaData, i);
                    Object value = getJdbcValue(context);
                    row.add(value);
                }
                rows.add(row);
            }
            // cap hit: probe one more row to know whether the result was truncated
            boolean truncated = rows.size() >= maxRows && resultSet.next();
            long fetchEnd = System.currentTimeMillis();

            sub.setHeaders(headers);
            sub.setRows(rows);
            sub.setColumns(columns);
            sub.setFetchRows(rows.size());
            sub.setTruncated(truncated);
            sub.setLimitApplied(truncated);
            sub.setFetchingMs(fetchEnd - fetchStart);
            addWarnings(resultSet.getWarnings(), result, sub);
        }
    }

    private void applyFirstResult(SqlCommandResult result, SqlCommandSubResult first) {
        result.setQuery(first.isQuery());
        result.setHeaders(first.getHeaders());
        result.setRows(first.getRows());
        result.setColumns(first.getColumns());
        result.setAffectedRows(first.getAffectedRows());
        result.setFetchRows(first.getFetchRows());
        result.setTruncated(first.getTruncated());
        result.setLimitApplied(first.getLimitApplied());
        result.setFetchingMs(first.getFetchingMs());
    }
}
