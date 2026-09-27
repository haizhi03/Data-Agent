package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.common.converter.db.SqlExecutionConverter;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.ConnectionAccessService;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.SqlExecutionService;
import edu.zsc.ai.plugin.capability.CommandExecutor;
import edu.zsc.ai.plugin.capability.SqlValidator;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlBatchCommandResult;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.sql.SqlValidationResult;
import edu.zsc.ai.plugin.model.transaction.BatchExecutionState;
import edu.zsc.ai.plugin.model.transaction.BatchMode;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecutionServiceImpl implements SqlExecutionService {

    private final ConnectionService connectionService;
    private final ConnectionAccessService connectionAccessService;

    @Override
    public ExecuteSqlResponse executeSql(AgentExecuteSqlRequest request) {
        connectionAccessService.assertWorkbenchApiAllowed();
        DbContext db = DbContext.from(request);
        String sql = request.getSql();

        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        DefaultPluginManager pluginManager = DefaultPluginManager.getInstance();
        SqlValidator validator = pluginManager.getSqlValidatorByPluginId(active.pluginId());

        if (validator.isTransactionControl(sql)) {
            return rejectedResponse(sql, db,
                    "Explicit transaction control is not supported; submit the statements as one batch", null);
        }

        CommandExecutor<SqlCommandRequest, SqlCommandResult> executor =
                pluginManager.getSqlCommandExecutorByPluginId(active.pluginId());
        SqlCommandResult result;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            SqlCommandRequest pluginRequest = pluginRequest(borrowed.connection(), db, sql, !isReadOnly(validator, sql));
            result = executor.executeCommand(pluginRequest);
        }

        ExecuteSqlResponse response = SqlExecutionConverter.toResponse(result);
        if (response != null) {
            response.setDatabaseName(db.catalog());
            response.setSchemaName(db.schema());
        }
        return response;
    }

    @Override
    public List<ExecuteSqlResponse> executeBatchSql(DbContext db, List<String> sqls) {
        return executeBatchSql(db, sqls, BatchMode.ATOMIC);
    }

    @Override
    public List<ExecuteSqlResponse> executeBatchSql(DbContext db, List<String> sqls, BatchMode mode) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        DefaultPluginManager pluginManager = DefaultPluginManager.getInstance();
        SqlValidator validator = pluginManager.getSqlValidatorByPluginId(active.pluginId());

        if (sqls.stream().anyMatch(validator::isTransactionControl)) {
            return sqls.stream()
                    .map(sql -> rejectedResponse(sql, db,
                            "Explicit transaction control is not supported; the batch is already transactional",
                            BatchExecutionState.FAILED))
                    .toList();
        }

        CommandExecutor<SqlCommandRequest, SqlCommandResult> executor =
                pluginManager.getSqlCommandExecutorByPluginId(active.pluginId());

        SqlBatchCommandResult batch;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();
            batch = mode == BatchMode.STEPWISE
                    ? executeStepwise(connection, executor, db, sqls)
                    : executeAtomic(connection, executor, validator, db, sqls);
        }
        return toResponses(batch, db);
    }

    /**
     * ATOMIC: the whole batch runs in one transaction. Historical per-statement facts are
     * never rewritten — after a confirmed rollback, executed statements are marked
     * ROLLED_BACK (or UNKNOWN past an implicit-committing DDL), the failed statement keeps
     * its own FAILED state, and the rest are NOT_EXECUTED.
     */
    private SqlBatchCommandResult executeAtomic(Connection connection,
                                                CommandExecutor<SqlCommandRequest, SqlCommandResult> executor,
                                                SqlValidator validator,
                                                DbContext db, List<String> sqls) {
        long started = System.currentTimeMillis();
        boolean originalAutoCommit = getAutoCommit(connection);
        List<SqlCommandResult> results = new ArrayList<>(sqls.size());
        int failedIndex = -1;
        TransactionOutcome transactionOutcome = TransactionOutcome.NONE;
        BatchExecutionState batchState;
        try {
            connection.setAutoCommit(false);
            for (int i = 0; i < sqls.size(); i++) {
                SqlCommandResult result = executor.executeCommand(
                        pluginRequest(connection, db, sqls.get(i), false));
                results.add(result);
                if (!result.isSuccess()) {
                    failedIndex = i;
                    break;
                }
            }

            if (failedIndex < 0) {
                try {
                    connection.commit();
                    transactionOutcome = TransactionOutcome.COMMITTED;
                    batchState = BatchExecutionState.SUCCESS;
                    for (SqlCommandResult result : results) {
                        result.setStatementState(StatementExecutionState.COMMITTED);
                        result.setTransactionOutcome(TransactionOutcome.COMMITTED);
                    }
                } catch (SQLException commitException) {
                    log.warn("Batch commit failed, outcome undecidable: {}", commitException.getMessage());
                    transactionOutcome = TransactionOutcome.UNKNOWN;
                    batchState = BatchExecutionState.UNKNOWN;
                    markUnknown(results);
                    if (rollbackQuietly(connection, commitException)) {
                        transactionOutcome = TransactionOutcome.ROLLED_BACK;
                        batchState = markConfirmedRollback(results, sqls, -1, validator);
                    }
                }
            } else if (rollbackQuietly(connection, null)) {
                transactionOutcome = TransactionOutcome.ROLLED_BACK;
                batchState = markConfirmedRollback(results, sqls, failedIndex, validator);
            } else {
                log.warn("Batch rollback failed, outcome undecidable");
                transactionOutcome = TransactionOutcome.UNKNOWN;
                batchState = BatchExecutionState.UNKNOWN;
                markExecutedUnknown(results, failedIndex);
            }
        } catch (SQLException e) {
            // batch never started (e.g. setAutoCommit refused)
            log.warn("Batch failed to start: {}", e.getMessage());
            batchState = BatchExecutionState.FAILED;
            transactionOutcome = TransactionOutcome.NONE;
        } catch (RuntimeException e) {
            log.warn("Batch aborted unexpectedly: {}", e.getMessage());
            rollbackQuietly(connection, e);
            batchState = BatchExecutionState.UNKNOWN;
            transactionOutcome = TransactionOutcome.UNKNOWN;
            markExecutedUnknown(results, results.size());
        } finally {
            restoreAutoCommit(connection, originalAutoCommit);
        }

        String skipMessage = transactionOutcome == TransactionOutcome.ROLLED_BACK
                ? "Not executed because the batch transaction was rolled back"
                : "Not executed because the batch failed";
        for (int i = results.size(); i < sqls.size(); i++) {
            results.add(notExecutedStub(sqls.get(i), skipMessage));
        }
        return batchResult(batchState, BatchMode.ATOMIC, transactionOutcome,
                failedIndex < 0 ? null : failedIndex, results, started);
    }

    /**
     * STEPWISE: every statement commits independently; execution continues after failures.
     */
    private SqlBatchCommandResult executeStepwise(Connection connection,
                                                  CommandExecutor<SqlCommandRequest, SqlCommandResult> executor,
                                                  DbContext db, List<String> sqls) {
        long started = System.currentTimeMillis();
        List<SqlCommandResult> results = new ArrayList<>(sqls.size());
        for (String sql : sqls) {
            results.add(executor.executeCommand(pluginRequest(connection, db, sql, true)));
        }

        int failedIndex = -1;
        int committed = 0;
        boolean undecidable = false;
        for (int i = 0; i < results.size(); i++) {
            SqlCommandResult result = results.get(i);
            if (!result.isSuccess() && failedIndex < 0) {
                failedIndex = i;
            }
            if (result.getStatementState() == StatementExecutionState.COMMITTED) {
                committed++;
            }
            if (result.getTransactionOutcome() == TransactionOutcome.UNKNOWN) {
                undecidable = true;
            }
        }

        BatchExecutionState batchState;
        TransactionOutcome transactionOutcome;
        if (undecidable) {
            batchState = BatchExecutionState.UNKNOWN;
            transactionOutcome = TransactionOutcome.UNKNOWN;
        } else if (committed == results.size()) {
            batchState = BatchExecutionState.SUCCESS;
            transactionOutcome = TransactionOutcome.COMMITTED;
        } else if (committed == 0) {
            batchState = BatchExecutionState.FAILED;
            transactionOutcome = TransactionOutcome.NONE;
        } else {
            batchState = BatchExecutionState.PARTIAL;
            transactionOutcome = TransactionOutcome.NONE;
        }
        return batchResult(batchState, BatchMode.STEPWISE, transactionOutcome,
                failedIndex < 0 ? null : failedIndex, results, started);
    }

    /**
     * Mark per-statement states after a confirmed rollback of the batch transaction.
     *
     * <p>A successfully executed DDL implicitly commits the pending transaction on engines
     * like DM (verified live on DM8), Oracle and MySQL, so statements up to and including
     * the last executed DDL cannot be asserted rolled back — they are UNKNOWN.
     *
     * @param failedIndex index of the failed statement (kept FAILED), or -1 when marking
     *                    after a commit failure
     * @return ROLLED_BACK when every executed statement is provably rolled back, UNKNOWN otherwise
     */
    private BatchExecutionState markConfirmedRollback(List<SqlCommandResult> results, List<String> sqls,
                                                      int failedIndex, SqlValidator validator) {
        int executedSuccessfully = failedIndex < 0 ? results.size() : failedIndex;
        int lastDdl = -1;
        for (int i = 0; i < executedSuccessfully; i++) {
            if (isDdl(validator, sqls.get(i))) {
                lastDdl = i;
            }
        }
        for (int i = 0; i < results.size(); i++) {
            if (i == failedIndex) {
                continue;
            }
            SqlCommandResult result = results.get(i);
            if (i <= lastDdl) {
                result.setStatementState(StatementExecutionState.UNKNOWN);
                result.setTransactionOutcome(TransactionOutcome.UNKNOWN);
            } else {
                result.setStatementState(StatementExecutionState.ROLLED_BACK);
                result.setTransactionOutcome(TransactionOutcome.ROLLED_BACK);
            }
        }
        return lastDdl >= 0 ? BatchExecutionState.UNKNOWN : BatchExecutionState.ROLLED_BACK;
    }

    private void markUnknown(List<SqlCommandResult> results) {
        for (SqlCommandResult result : results) {
            result.setStatementState(StatementExecutionState.UNKNOWN);
            result.setTransactionOutcome(TransactionOutcome.UNKNOWN);
        }
    }

    /** After an unconfirmable rollback, previously executed statements can no longer be asserted. */
    private void markExecutedUnknown(List<SqlCommandResult> results, int failedIndex) {
        for (int i = 0; i < results.size(); i++) {
            if (i == failedIndex) {
                continue;
            }
            results.get(i).setStatementState(StatementExecutionState.UNKNOWN);
            results.get(i).setTransactionOutcome(TransactionOutcome.UNKNOWN);
        }
    }

    private SqlBatchCommandResult batchResult(BatchExecutionState batchState, BatchMode mode,
                                              TransactionOutcome transactionOutcome, Integer failedIndex,
                                              List<SqlCommandResult> results, long started) {
        SqlBatchCommandResult batch = new SqlBatchCommandResult();
        batch.setSuccess(batchState == BatchExecutionState.SUCCESS);
        batch.setBatchState(batchState);
        batch.setMode(mode);
        batch.setTransactionOutcome(transactionOutcome);
        batch.setFailedIndex(failedIndex);
        batch.setResults(results);
        batch.setExecutionTime(System.currentTimeMillis() - started);
        return batch;
    }

    private List<ExecuteSqlResponse> toResponses(SqlBatchCommandResult batch, DbContext db) {
        List<ExecuteSqlResponse> responses = new ArrayList<>(batch.getResults().size());
        for (SqlCommandResult result : batch.getResults()) {
            ExecuteSqlResponse response = SqlExecutionConverter.toResponse(result, batch.getBatchState());
            if (response != null) {
                response.setDatabaseName(db.catalog());
                response.setSchemaName(db.schema());
            }
            responses.add(response);
        }
        return responses;
    }

    private SqlCommandRequest pluginRequest(Connection connection, DbContext db, String sql, boolean needTransaction) {
        SqlCommandRequest request = new SqlCommandRequest();
        request.setConnection(connection);
        request.setOriginalSql(sql);
        request.setExecuteSql(sql);
        request.setDatabase(db.catalog());
        request.setSchema(db.schema());
        request.setNeedTransaction(needTransaction);
        return request;
    }

    /**
     * Read-only statements skip the explicit transaction (no useless commit round-trip).
     * Anything the plugin validator cannot confidently classify as read-only keeps the
     * transactional path.
     */
    private boolean isReadOnly(SqlValidator validator, String sql) {
        try {
            SqlValidationResult validation = validator.validate(sql);
            return validation != null && validation.valid()
                    && validation.sqlType() != null && validation.sqlType().isReadOnly();
        } catch (RuntimeException e) {
            log.warn("SQL validation failed, keeping transactional execution: {}", e.getMessage());
            return false;
        }
    }

    private boolean isDdl(SqlValidator validator, String sql) {
        try {
            return validator.classifySql(sql).isDdl();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private SqlCommandResult notExecutedStub(String sql, String message) {
        SqlCommandResult stub = new SqlCommandResult();
        stub.setSuccess(false);
        stub.setOriginalSql(sql);
        stub.setExecutedSql(sql);
        stub.setErrorMessage(message);
        stub.setStatementState(StatementExecutionState.NOT_EXECUTED);
        stub.setTransactionOutcome(TransactionOutcome.NONE);
        return stub;
    }

    private ExecuteSqlResponse rejectedResponse(String sql, DbContext db, String message,
                                                BatchExecutionState batchState) {
        return ExecuteSqlResponse.builder()
                .success(false)
                .errorMessage(message)
                .originalSql(sql)
                .databaseName(db.catalog())
                .schemaName(db.schema())
                .statementState(StatementExecutionState.NOT_EXECUTED)
                .transactionOutcome(TransactionOutcome.NONE)
                .batchState(batchState)
                .build();
    }

    private boolean getAutoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read connection auto-commit state", e);
        }
    }

    private boolean rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException rollbackException) {
            if (original != null) {
                original.addSuppressed(rollbackException);
            }
            log.warn("Failed to roll back batch transaction", rollbackException);
            return false;
        }
    }

    private void restoreAutoCommit(Connection connection, boolean originalAutoCommit) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException e) {
            log.warn("Failed to restore connection auto-commit state", e);
        }
    }
}
