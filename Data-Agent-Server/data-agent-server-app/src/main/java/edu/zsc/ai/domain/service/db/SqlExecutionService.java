package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.plugin.model.transaction.BatchMode;

import java.util.List;

/**
 * Service for executing SQL on a user-owned connection.
 */
public interface SqlExecutionService {

    /**
     * Execute SQL in the context of the given request (connection, database, schema).
     *
     * @param request execution context and SQL
     * @return execution result (query result set or DML affected rows, or error info)
     */
    ExecuteSqlResponse executeSql(AgentExecuteSqlRequest request);

    /**
     * Execute multiple SQL statements in batch, opening the connection once.
     * Defaults to {@link BatchMode#ATOMIC}: the batch runs in one transaction and any
     * failure rolls it back. Per-statement facts (success flag, original error message)
     * are never rewritten — batch semantics are expressed via statementState/batchState.
     *
     * @param db   target database context
     * @param sqls list of SQL statements to execute
     * @return one response per statement, in the same order as input
     */
    List<ExecuteSqlResponse> executeBatchSql(DbContext db, List<String> sqls);

    /**
     * Execute multiple SQL statements in batch with explicit semantics.
     * {@link BatchMode#STEPWISE} commits each statement independently and continues
     * after failures (batch state PARTIAL/SUCCESS/FAILED).
     *
     * @param db   target database context
     * @param sqls list of SQL statements to execute
     * @param mode batch transaction semantics
     * @return one response per statement, in the same order as input
     */
    List<ExecuteSqlResponse> executeBatchSql(DbContext db, List<String> sqls, BatchMode mode);
}
