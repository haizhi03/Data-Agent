package edu.zsc.ai.plugin.model.command.sql;

import edu.zsc.ai.plugin.model.command.CommandRequest;
import edu.zsc.ai.plugin.model.transaction.BatchMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Connection;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SqlCommandRequest implements CommandRequest {

    private Connection connection;

    private String originalSql;

    private String executeSql;

    private String database;

    private String schema;

    private boolean needTransaction;

    /** PreparedStatement parameters, null or empty uses Statement */
    private Object[] params;

    /** Batch semantics when this request is part of a batch; defaults to ATOMIC. */
    private BatchMode batchMode = BatchMode.ATOMIC;

    /** Statement timeout in milliseconds; null means no limit. Mapped to Statement.setQueryTimeout (seconds). */
    private Integer timeoutMs;

    /**
     * Optional execution identity. When set, the executing Statement is registered in
     * {@link edu.zsc.ai.plugin.execution.StatementCancellationRegistry} under this id so a
     * cancel requester (e.g. an async task manager using the same id) can trigger
     * Statement.cancel(). Convention: async task id == execution id.
     */
    private String executionId;

    /**
     * Maximum rows materialized into the result per result set; null uses the executor
     * default cap, a value &lt;= 0 means unlimited. When the cap is hit the result is
     * marked truncated/limitApplied.
     */
    private Integer maxRows;

    /** JDBC fetch size hint; null leaves the driver default. */
    private Integer fetchSize;

    @Override
    public String getCommand() {
        return originalSql;
    }

    public static SqlCommandRequest ofWithoutTransaction(Connection connection, String originalSql, String executeSql,
                                                         String database, String schema) {
        SqlCommandRequest request = new SqlCommandRequest();
        request.setConnection(connection);
        request.setOriginalSql(originalSql);
        request.setExecuteSql(executeSql);
        request.setDatabase(database);
        request.setSchema(schema);
        request.setNeedTransaction(false);
        return request;
    }
}
