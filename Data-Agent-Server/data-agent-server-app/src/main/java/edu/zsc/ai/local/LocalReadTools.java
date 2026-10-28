package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.service.db.*;
import edu.zsc.ai.domain.service.db.impl.SqlExecutionServiceImpl;
import java.util.*;
import java.util.function.Supplier;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** An explicit local tool allowlist. Targets and versions come from the server session, never model arguments. */
final class LocalReadTools {
    private final LocalConversationRegistry.Session session;
    private final ModelRunSnapshot run;
    private final LocalConnectionService connections;
    private final TableService tables;
    private final ColumnService columns;
    private final SqlExecutionServiceImpl sql;
    private final ObjectMapper json;
    private final LocalWriteService writes;
    private final RequestContextInfo request;
    private final AgentRequestContextInfo agent;
    private final java.util.concurrent.atomic.AtomicInteger toolCount=new java.util.concurrent.atomic.AtomicInteger();
    LocalReadTools(LocalConversationRegistry.Session session, ModelRunSnapshot run, LocalConnectionService connections,
            TableService tables, ColumnService columns, SqlExecutionServiceImpl sql, ObjectMapper json,
            RequestContextInfo request, AgentRequestContextInfo agent) {
        this(session,run,connections,tables,columns,sql,json,request,agent,null);
    }
    LocalReadTools(LocalConversationRegistry.Session session, ModelRunSnapshot run, LocalConnectionService connections,
            TableService tables, ColumnService columns, SqlExecutionServiceImpl sql, ObjectMapper json,
            RequestContextInfo request, AgentRequestContextInfo agent, LocalWriteService writes) {
        this.writes=writes;
        this.session=session;this.run=run;this.connections=connections;this.tables=tables;this.columns=columns;
        this.sql=sql;this.json=json;this.request=request;this.agent=agent;
    }
    @Tool("List tables in the user's fixed database target. Returns a bounded list and a truncation flag.")
    public String list_tables() {
        return scoped(() -> { var result=tables.getTables(session.target); return Map.of("tables",result.stream().limit(200).toList(),"truncated",result.size()>200); });
    }
    @Tool("Read columns for an unqualified table name in the fixed database target.")
    public String describe_table(String tableName) {
        return scoped(() -> {
            if (tableName == null || !tableName.matches("[\\p{L}\\p{N}_$#]{1,128}")) throw LocalRequestException.invalid();
            var result=columns.listColumns(session.target,tableName);
            return Map.of("columns",result.stream().limit(100).toList(),"truncated",result.size()>100);
        });
    }
    @Tool("Execute one supported read-only SELECT or WITH query in the fixed target. Writes, locks, sequences and unknown functions are forbidden. Result is bounded.")
    public String query_database(String query) {
        return scoped(() -> {
            validateTarget(query,session.target.catalog(),session.target.schema());
            String executionId=UUID.randomUUID().toString();
            try (var ignored=run.track(() -> sql.cancelExecution(session.target,executionId))) {
                run.ensureActive();
                return sql.executeSql(AgentExecuteSqlRequest.builder().conversationId(session.id)
                        .connectionId(session.target.connectionId()).catalog(session.target.catalog()).schema(session.target.schema())
                        .sql(query).timeoutMs(15000).maxRows(200).executionId(executionId).build());
            }
        });
    }
    @Tool("Propose one INSERT, UPDATE or DELETE for explicit user approval. This never executes SQL. No functions, subqueries, DDL or batch statements. Wait for the user to approve in the application; never claim it executed.")
    public String propose_write(String statement) {
        return scoped(() -> {
            if (writes==null) throw LocalRequestException.forbidden();
            return writes.propose(session,run,statement);
        });
    }
    private String scoped(Supplier<?> action) {
        try (var ignored=new ModelContextScope(request,agent)) {
            run.ensureActive();
            if (toolCount.incrementAndGet()>32) throw new edu.zsc.ai.config.ai.model.ModelIntegrationException("AGENT_BUDGET_EXCEEDED",org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
            if (session.closed || session.target.connectionId()==null) throw LocalRequestException.forbidden();
            if (connections.get(session.target.connectionId()).version()!=session.connectionVersion) {
                throw new LocalRequestException(409,"CONNECTION_REVISION_CONFLICT","连接已修改，请新建对话");
            }
            Object result=connections.withExpectedVersion(session.target.connectionId(),session.connectionVersion,action);
            run.ensureActive();
            String text=json.writeValueAsString(result);
            return text.length()<=12000?text:json.writeValueAsString(Map.of("truncated",true,"preview",text.substring(0,11000)));
        } catch (LocalRequestException e) { return safeError(e.response().reasonCode()); }
        catch (edu.zsc.ai.config.ai.model.ModelIntegrationException e) { throw e; }
        catch (Exception e) { return safeError("LOCAL_TOOL_FAILED"); }
    }
    private static String safeError(String reason) { return "{\"error\":\""+reason+"\"}"; }
    static void validateTarget(String query, String catalog, String schema) {
        LocalReadOnlySql.validate(query);
        try {
            validateNamespace(query,catalog,schema);
        } catch (LocalRequestException e) { throw e; }
        catch (Exception e) { throw LocalReadOnlySql.unsupported(); }
    }
    static void validateNamespace(String query, String catalog, String schema) {
        try {
            var statements=CCJSqlParserUtil.parseStatements(query).getStatements();
            if (statements.size()!=1) throw LocalReadOnlySql.unsupported();
            new TablesNamesFinder() {
                @Override protected String extractTableName(Table table) {
                    String qualifier=unquote(table.getSchemaName());
                    String database=table.getDatabase()==null?null:unquote(table.getDatabase().getDatabaseName());
                    String namespace=schema==null?catalog:schema;
                    if (namespace!=null && qualifier!=null && !namespace.equals(qualifier)) throw LocalRequestException.forbidden();
                    if (database!=null && (catalog==null || !catalog.equals(database))) throw LocalRequestException.forbidden();
                    return super.extractTableName(table);
                }
            }.getTableList(statements.getFirst());
        } catch (LocalRequestException e) { throw e; }
        catch (Exception e) { throw LocalReadOnlySql.unsupported(); }
    }
    private static String unquote(String value) {
        if (value==null || value.isBlank()) return null;
        if (value.startsWith("\"") && value.endsWith("\"")) return value.substring(1,value.length()-1).replace("\"\"","\"");
        if (value.startsWith("`") && value.endsWith("`")) return value.substring(1,value.length()-1).replace("``","`");
        return value;
    }
}
