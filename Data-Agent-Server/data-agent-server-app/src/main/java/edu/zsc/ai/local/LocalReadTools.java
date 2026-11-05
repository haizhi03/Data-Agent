package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import edu.zsc.ai.context.*;
import edu.zsc.ai.api.model.request.ChatUserMention;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.*;
import java.util.*;
import java.util.function.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** Defaults are fallbacks; every explicit tool target is validated against local connection access. */
final class LocalReadTools {
    private final LocalConversationRegistry.Session session;
    private final ModelRunSnapshot run;
    private final LocalConnectionService connections;
    private final LocalMetadataTools metadata;
    private final LocalReadOnlyQuery queries;
    private final ObjectMapper json;
    private final LocalWriteService writes;
    private final RequestContextInfo request;
    private final AgentRequestContextInfo agent;
    private final DbContext defaultTarget;
    private final Map<Long,Long> versions=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger toolCount=new java.util.concurrent.atomic.AtomicInteger();
    LocalReadTools(LocalConversationRegistry.Session session, ModelRunSnapshot run, LocalConnectionService connections,
            LocalMetadataTools metadata, LocalReadOnlyQuery queries, ObjectMapper json,
            RequestContextInfo request, AgentRequestContextInfo agent) {
        this(session,run,connections,metadata,queries,json,request,agent,null);
    }
    LocalReadTools(LocalConversationRegistry.Session session, ModelRunSnapshot run, LocalConnectionService connections,
            LocalMetadataTools metadata, LocalReadOnlyQuery queries, ObjectMapper json,
            RequestContextInfo request, AgentRequestContextInfo agent, LocalWriteService writes) {
        this(session,run,connections,metadata,queries,json,request,agent,writes,session.target,session.connectionVersion,List.of());
    }
    LocalReadTools(LocalConversationRegistry.Session session, ModelRunSnapshot run, LocalConnectionService connections,
            LocalMetadataTools metadata, LocalReadOnlyQuery queries, ObjectMapper json,
            RequestContextInfo request, AgentRequestContextInfo agent, LocalWriteService writes,
            DbContext target, Long version, List<ChatUserMention> mentions) {
        this.writes=writes;this.session=session;this.run=run;this.connections=connections;this.metadata=metadata;
        this.queries=queries;this.json=json;this.request=request;this.agent=agent;this.defaultTarget=target;
        if(target.connectionId()!=null && version!=null) versions.put(target.connectionId(),version);
        for(var mention:mentions) versions.computeIfAbsent(mention.getConnectionId(),id -> connections.get(id).version());
    }
    @Tool("List available data sources (connectionId, name, dbType) without a default connection. Discover these before asking the user for a connection ID; this does not open any database.")
    public String list_connections() {
        return toolAction(request, () -> metadata.listConnections(versions));
    }

    @Tool("Discover databases/catalogs or schemas on one accessible connection. With no catalog, lists catalogs if supported; otherwise lists schemas. Supply a chosen catalog to list its schemas. Check truncated; never guess namespace names.")
    public String list_namespaces(@P(value="Connection ID from list_connections or context",required=false) Long connectionId,
            @P(value="Chosen catalog, when applicable",required=false) String catalogName,
            @P(value="Schema, when applicable",required=false) String schemaName) {
        return scoped(connectionId,catalogName,schemaName,metadata::listNamespaces);
    }
    @Tool("List bounded tables in one known database/schema. Discover missing namespaces with list_namespaces first. Omitted target fields use the conversation default. Explicit connection/catalog/schema may reference another accessible target.")
    public String list_tables(@P(value="Connection ID",required=false) Long connectionId,
            @P(value="Catalog, when applicable",required=false) String catalogName,
            @P(value="Schema, when applicable",required=false) String schemaName) {
        return scoped(connectionId,catalogName,schemaName,metadata::listTables);
    }
    public String list_tables() { return list_tables(null,null,null); }

    @Tool("Read columns for an unqualified table name. Use the full identity of an explicit reference; only unspecified target fields use defaults.")
    public String describe_table(String tableName,@P(value="Connection ID",required=false) Long connectionId,
            @P(value="Catalog, when applicable",required=false) String catalogName,
            @P(value="Schema, when applicable",required=false) String schemaName) {
        return scoped(connectionId,catalogName,schemaName,target -> metadata.describeTable(target,tableName));
    }
    public String describe_table(String tableName) { return describe_table(tableName,null,null,null); }

    @Tool("Execute one supported read-only SELECT/WITH. Choose an accessible connection; qualified names may span namespaces. Writes, locks, sequences and unknown functions remain forbidden.")
    public String query_database(String query,@P(value="Connection ID",required=false) Long connectionId,
            @P(value="Default catalog for unqualified SQL names",required=false) String catalogName,
            @P(value="Default schema for unqualified SQL names",required=false) String schemaName) {
        return scoped(connectionId,catalogName,schemaName,target -> queries.execute(session.id,run,target,query).response());
    }
    public String query_database(String query) { return query_database(query,null,null,null); }

    @Tool("Propose one restricted INSERT/UPDATE/DELETE for explicit application approval. This never executes SQL. Target and SQL are bound immutably to the proposal. No DDL, functions, subqueries or batch statements.")
    public String propose_write(String statement,@P(value="Connection ID",required=false) Long connectionId,
            @P(value="Catalog, when applicable",required=false) String catalogName,
            @P(value="Schema, when applicable",required=false) String schemaName) {
        return scoped(connectionId,catalogName,schemaName,target -> {
            if(writes==null) throw LocalRequestException.forbidden();
            return writes.propose(session,run,statement,target,versions.get(target.connectionId()));
        });
    }
    public String propose_write(String statement) { return propose_write(statement,null,null,null); }

    private String scoped(Long connectionId,String catalogName,String schemaName,Function<DbContext,?> action) {
        // Fill defaults first so the request context can be scoped before any access check runs.
        var probe=LocalMetadataTools.fillDefaults(connectionId,catalogName,schemaName,defaultTarget);
        return toolAction(request.toBuilder().connectionId(probe.connectionId()).catalog(probe.catalog()).schema(probe.schema()).build(), () -> {
            var scope=metadata.resolveValidated(probe,versions);
            return metadata.withPinnedVersion(scope,() -> action.apply(scope.target()));
        });
    }
    private String toolAction(RequestContextInfo context,java.util.function.Supplier<?> action) {
        try(var ignored=new ModelContextScope(context,agent)) {
            run.ensureActive();
            if(toolCount.incrementAndGet()>32) throw new edu.zsc.ai.config.ai.model.ModelIntegrationException("AGENT_BUDGET_EXCEEDED",org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
            if(session.closed) throw LocalRequestException.forbidden();
            Object result=action.get();
            run.ensureActive();String text=json.writeValueAsString(result);
            return text.length()<=12000?text:json.writeValueAsString(Map.of("truncated",true,"preview",text.substring(0,11000)));
        } catch(LocalRequestException e) { return safeError(e.response().reasonCode()); }
        catch(edu.zsc.ai.config.ai.model.ModelIntegrationException e) { throw e; }
        catch(Exception e) { return safeError("LOCAL_TOOL_FAILED"); }
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
