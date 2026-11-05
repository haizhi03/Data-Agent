package edu.zsc.ai.local;

import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import edu.zsc.ai.config.ai.model.ModelIntegrationException;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.ModelContextScope;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.impl.SqlExecutionServiceImpl;
import java.util.UUID;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

/** Shared restricted execution; callers must establish trusted context and pin the connection version. */
final class LocalReadOnlyQuery {
    static final int TIMEOUT_MS = 15000;
    static final int MAX_ROWS = 200;
    private final SqlExecutionServiceImpl sql;

    LocalReadOnlyQuery(SqlExecutionServiceImpl sql) { this.sql = sql; }

    record Result(String executionId, ExecuteSqlResponse response) { }

    Result execute(long conversationId, ModelRunSnapshot run, DbContext target, String query) {
        LocalReadOnlySql.validate(query);
        try {
            if (CCJSqlParserUtil.parseStatements(query).getStatements().size() != 1) throw LocalReadOnlySql.unsupported();
        } catch (LocalRequestException e) { throw e; }
        catch (Exception e) { throw LocalReadOnlySql.unsupported(); }
        String executionId = UUID.randomUUID().toString();
        var requestContext = RequestContext.snapshot();
        var agentContext = AgentRequestContext.snapshot();
        try (var ignored = run.track(() -> {
            try (var scope = new ModelContextScope(requestContext, agentContext)) { sql.cancelExecution(target, executionId); }
        })) {
            run.ensureActive();
            var response = sql.executeSql(AgentExecuteSqlRequest.builder().conversationId(conversationId)
                    .connectionId(target.connectionId()).catalog(target.catalog()).schema(target.schema()).sql(query)
                    .timeoutMs(TIMEOUT_MS).maxRows(MAX_ROWS).executionId(executionId).build());
            run.ensureActive();
            if (response == null) throw new LocalRequestException(502, "LOCAL_QUERY_FAILED", "查询未返回执行结果");
            return new Result(executionId, response);
        } catch (LocalRequestException | ModelIntegrationException e) { throw e; }
        catch (RuntimeException e) { throw new LocalRequestException(502, "LOCAL_QUERY_FAILED", "查询执行失败"); }
    }
}
