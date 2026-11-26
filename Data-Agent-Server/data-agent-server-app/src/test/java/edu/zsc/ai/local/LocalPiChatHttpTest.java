package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.*;
import edu.zsc.ai.DataAgentApplication;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResultSet;
import edu.zsc.ai.domain.service.db.impl.SqlExecutionServiceImpl;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import edu.zsc.ai.plugin.model.transaction.TransactionOutcome;
import edu.zsc.ai.domain.service.db.ColumnService;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Layer-1 Pi verification: real HTTP/SSE and a real Node child process running a
 * deterministic protocol fixture. No model vendor and no business database calls.
 */
@ExtendWith(OutputCaptureExtension.class)
class LocalPiChatHttpTest {
    @TempDir static Path root;
    static ServletWebServerApplicationContext context;
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static String base, token, runtime, modelId;
    static long connectionId;
    static final TableService TABLES=mock(TableService.class);
    static final edu.zsc.ai.domain.service.db.DatabaseService DATABASES=mock(edu.zsc.ai.domain.service.db.DatabaseService.class);
    static final edu.zsc.ai.domain.service.db.SchemaService SCHEMAS=mock(edu.zsc.ai.domain.service.db.SchemaService.class);
    static final ColumnService COLUMNS=mock(ColumnService.class);
    static final LocalForeignKeyReader FOREIGN_KEYS=mock(LocalForeignKeyReader.class);
    static final SqlExecutionServiceImpl SQL=mock(SqlExecutionServiceImpl.class);
    static final AtomicReference<CountDownLatch> QUERY_STARTED=new AtomicReference<>(new CountDownLatch(1));
    static final AtomicReference<CountDownLatch> QUERY_RELEASE=new AtomicReference<>(new CountDownLatch(1));
    static final List<Long> createdSessions=new CopyOnWriteArrayList<>();

    @Configuration(proxyBeanMethods=false) static class Fixture {
        @Bean @Primary LocalForeignKeyReader fixtureForeignKeys() {
            when(FOREIGN_KEYS.read(any(),any())).thenReturn(Map.of("available",true,"keys",List.of(),"truncated",false));
            return FOREIGN_KEYS;
        }
        @Bean @Primary edu.zsc.ai.domain.service.db.DatabaseService fixtureDatabases() {
            when(DATABASES.getDatabases(any())).thenReturn(List.of("fixture_db")); return DATABASES;
        }
        @Bean @Primary edu.zsc.ai.domain.service.db.SchemaService fixtureSchemas() {
            when(SCHEMAS.listSchemas(any(),any())).thenReturn(List.of("APP")); return SCHEMAS;
        }
        @Bean @Primary SqlExecutionServiceImpl fixtureSql() {
            when(SQL.executeSql(any())).thenAnswer(invocation -> {
                AgentExecuteSqlRequest request=invocation.getArgument(0);
                assertThat(edu.zsc.ai.context.RequestContext.snapshot().getConnectionId()).isEqualTo(request.getConnectionId());
                assertThat(edu.zsc.ai.context.AgentRequestContext.get().getModelRunSnapshot()).isNotNull();
                if(request.getSql().contains("wait-query")) {
                    QUERY_STARTED.get().countDown();
                    assertThat(QUERY_RELEASE.get().await(15,TimeUnit.SECONDS)).isTrue();
                }
                var result=new ExecuteSqlResponse(); result.setQuery(true);
                boolean failed=request.getSql().contains("timeout-query"); result.setSuccess(!failed);
                result.setStatementState(failed?StatementExecutionState.TIMED_OUT:StatementExecutionState.EXECUTED);
                result.setTransactionOutcome(failed?TransactionOutcome.UNKNOWN:TransactionOutcome.NONE);
                if(failed) result.setErrorMessage("fixture query timeout");
                result.setHeaders(List.of("ROW_COUNT"));
                result.setRows(List.of(List.of(request.getSql().contains("large-query")?"\"\\\n".repeat(9000):7)));
                result.setResultSet(new ExecuteSqlResultSet(null,result.getRows(),1,request.getSql().contains("truncated-query")));
                return result;
            });
            when(SQL.cancelExecution(any(),any())).thenAnswer(invocation -> {
                DbContext target=invocation.getArgument(0);
                assertThat(edu.zsc.ai.context.RequestContext.getConnectionId()).isEqualTo(target.connectionId());
                assertThat(edu.zsc.ai.context.AgentRequestContext.get().getModelRunSnapshot()).isNotNull();
                QUERY_RELEASE.get().countDown(); return null;
            });
            return SQL;
        }
        @Bean @Primary TableService fixtureTables() {
            when(TABLES.getTables(any())).thenReturn(List.of("safe_table")); return TABLES;
        }
        @Bean @Primary ColumnService fixtureColumns() {
            when(COLUMNS.listColumns(any(),any())).thenReturn(List.of(
                    new ColumnMetadata("id",4,"BIGINT",20,0,false,1,"主键",true,false,false,null),
                    new ColumnMetadata("name",12,"VARCHAR",255,0,true,2,"名称",false,false,false,null)));
            return COLUMNS;
        }
        @Bean @Primary ModelConnectionProbe fixtureConnectionProbe() {
            return new ModelConnectionProbe() { @Override public void test(ResolvedModelConfig config,String secret) { } };
        }
    }

    static boolean nodeAvailable() {
        try { return new ProcessBuilder("node","--version").start().waitFor(5,TimeUnit.SECONDS); }
        catch (Exception e) { return false; }
    }

    @BeforeAll static void start() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(),"node runtime is required for Pi worker tests");
        Path worker=Path.of("src/test/resources/pi-fixture-worker.mjs").toAbsolutePath();
        assertThat(worker).isRegularFile();
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(DataAgentApplication.class,Fixture.class).run(
                "--app.runtime.mode=local","--server.port=0","--spring.main.banner-mode=off","--app.local.data-directory="+root,
                "--app.local.pi.enabled=true","--app.local.pi.worker-script="+worker,
                "--app.local.pi.startup-timeout-ms=20000","--app.local.pi.cancel-grace-seconds=3");
        base="http://127.0.0.1:"+context.getWebServer().getPort(); var identity=context.getBean(LocalRuntimeIdentity.class);
        token=identity.transportTokenForHost(); runtime=identity.runtimeInstanceId().toString();
        var setup=new LinkedHashMap<String,Object>();
        setup.put("name","Fixture"); setup.put("provider","CUSTOM"); setup.put("apiStyle","OPENAI_CHAT_COMPLETIONS");
        setup.put("endpoint","https://fixture.example.invalid/v1/"); setup.put("compatibilityProfile","openai-chat-v1");
        setup.put("apiKey","FIXTURE_SECRET_NEVER_PRINT"); setup.put("modelId","fixture-model"); setup.put("capabilityProfile","text-tools-v1");
        setup.put("defaultOptions",Map.of("thinkingMode","DISABLED","maxOutputTokens",2048));
        setup.put("maxContextTokens",32768); setup.put("memoryThreshold",4000); setup.put("enabled",true); setup.put("chatVisible",true); setup.put("makeDefault",true);
        var versions=new LinkedHashMap<String,Integer>(); for(var purpose:ModelPurpose.values())versions.put(purpose.name(),0); setup.put("bindingVersions",versions);
        var saved=call("POST","/api/ai/model-setups",setup,true);
        assertThat(saved.statusCode()).isEqualTo(200);
        modelId=JSON.readTree(saved.body()).path("data").path("model").path("id").asText();
        var explorerSetup=new LinkedHashMap<>(setup);
        explorerSetup.put("name","Explorer fixture");explorerSetup.put("modelId","explorer-fixture-model");
        explorerSetup.put("makeDefault",false);explorerSetup.remove("bindingVersions");
        var explorerSaved=call("POST","/api/ai/model-setups",explorerSetup,true);
        String explorerId=JSON.readTree(explorerSaved.body()).path("data").path("model").path("id").asText();
        call("PUT","/api/ai/model-bindings/EXPLORER",Map.of("expectedVersion",1,"modelConfigId",explorerId),true);
        Path driver=Files.write(root.resolve("fixture-driver.jar"),new byte[0]);
        var connection=call("POST","/api/connections/create",Map.of("name","Fixture db","dbType","mysql","host","example.invalid","port",3306,"username","fixture","driverJarPath",driver.toString()),true);
        assertThat(connection.statusCode()).isEqualTo(200); connectionId=JSON.readTree(connection.body()).path("data").path("id").asLong();
    }
    @AfterAll static void close() { if(context!=null)context.close(); }
    @AfterEach void releaseSessions() throws Exception {
        for(long id:createdSessions)call("DELETE","/api/local/conversations/"+id,null,false);
        createdSessions.clear();
    }

    static HttpRequest request(String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(60))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .header("X-Local-Runtime-Id",runtime);
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }
    static HttpResponse<String> call(String method,String path,Object body,boolean checked) throws Exception {
        var response=HTTP.send(request(method,path,body),HttpResponse.BodyHandlers.ofString());
        if (checked) assertThat(response.statusCode()).as(path+" -> %s",response.body()).isEqualTo(200);
        return response;
    }
    static long session(boolean database) throws Exception {
        var created=call("POST","/api/local/conversations",database?Map.of("connectionId",connectionId,"catalog","fixture_db"):Map.of(),true);
        long id=JSON.readTree(created.body()).path("data").path("conversationId").asLong();createdSessions.add(id);return id;
    }
    static String piTurn(long id,String text,List<Map<String,Object>> mentions) throws Exception {
        var body=new LinkedHashMap<String,Object>();
        body.put("conversationId",id); body.put("message",text); body.put("modelConfigId",modelId);
        if (mentions!=null) body.put("userMentions",mentions);
        return call("POST","/api/local/pi/chat/stream",body,true).body();
    }
    static List<JsonNode> blocks(String sse) throws Exception {
        var result=new ArrayList<JsonNode>();
        for(String line:sse.split("\n")) if(line.startsWith("data:")) result.add(JSON.readTree(line.substring(5).trim()));
        return result;
    }
    static JsonNode queryTool(String sse) throws Exception {
        for(var block:blocks(sse)) if(block.path("type").asText().equals("TOOL_RESULT")) {
            var tool=JSON.readTree(block.path("data").asText());
            if(tool.path("toolName").asText().equals("query_database")) return tool;
        }
        throw new AssertionError("query result missing");
    }
    static JsonNode discoveryResult(String sse) throws Exception {
        for(var block:blocks(sse))if(block.path("type").asText().equals("TOOL_RESULT")) {
            return JSON.readTree(JSON.readTree(block.path("data").asText()).path("result").asText());
        }
        throw new AssertionError("Discovery result missing");
    }

    @Test void noDefaultConnectionCanDiscoverSourcesThenReadTheChosenDatabase() throws Exception {
        long id=session(false); clearInvocations(TABLES,SQL,DATABASES,SCHEMAS);
        var sources=discoveryResult(piTurn(id,"list_connections:{}",null));
        assertThat(sources.path("connections").findValuesAsText("name")).contains("Fixture db");
        for(var source:sources.path("connections")) assertThat(source.properties().stream().map(Map.Entry::getKey))
                .containsExactlyInAnyOrder("connectionId","name","dbType");
        verifyNoInteractions(TABLES,SQL,DATABASES,SCHEMAS);
        var namespaces=discoveryResult(piTurn(id,"list_namespaces:"+JSON.writeValueAsString(Map.of("connectionId",connectionId)),null));
        assertThat(namespaces.path("catalogs").get(0).asText()).isEqualTo("fixture_db");
        verify(DATABASES).getDatabases(connectionId);verifyNoInteractions(SCHEMAS);
        var tables=discoveryResult(piTurn(id,"list_tables:"+JSON.writeValueAsString(Map.of("connectionId",connectionId,"catalogName","fixture_db")),null));
        assertThat(tables.path("tables").get(0).asText()).isEqualTo("safe_table");
        verify(TABLES).getTables(new DbContext(connectionId,"fixture_db",null));
        assertThat(JSON.readTree(call("GET","/api/local/conversations/"+id,null,true).body()).path("data").path("defaultTarget").path("connectionId").isNull()).isTrue();
    }

    @Test void schemaOnlyDiscoveryDoesNotInventACatalogAndBadDiscoveryArgumentsFail() throws Exception {
        var created=call("POST","/api/connections/create",Map.of("name","Schema discovery","dbType","dm","host","example.invalid","port",5236,
                "username","fixture","driverJarPath",root.resolve("fixture-driver.jar").toString()),true);
        long other=JSON.readTree(created.body()).path("data").path("id").asLong();
        long id=session(false);clearInvocations(DATABASES,SCHEMAS,TABLES);
        var result=discoveryResult(piTurn(id,"list_namespaces:"+JSON.writeValueAsString(Map.of("connectionId",other,"catalogName","pseudo")),null));
        assertThat(result.path("supportsCatalog").asBoolean()).isFalse();
        assertThat(result.path("target").path("catalogName").isNull()).isTrue();
        assertThat(result.path("schemas").get(0).asText()).isEqualTo("APP");
        verify(SCHEMAS).listSchemas(other,null);verifyNoInteractions(DATABASES,TABLES);
        assertThat(piTurn(id,"list_connections:{\"connectionId\":1}",null)).contains("LOCAL_REQUEST_INVALID");
        assertThat(piTurn(id,"list_namespaces:{\"connectionId\":1.5}",null)).contains("LOCAL_REQUEST_INVALID");
        assertThat(piTurn(id,"list_namespaces:{}",null)).contains("LOCAL_SCOPE_FORBIDDEN");
        clearInvocations(TABLES,COLUMNS);
        assertThat(piTurn(id,"list_tables:"+JSON.writeValueAsString(Map.of("connectionId",connectionId)),null)).contains("LOCAL_NAMESPACE_REQUIRED");
        assertThat(piTurn(id,"list_tables:"+JSON.writeValueAsString(Map.of("connectionId",other)),null)).contains("LOCAL_NAMESPACE_REQUIRED");
        verifyNoInteractions(TABLES,COLUMNS);
    }

    static JsonNode explorerResult(String sse) throws Exception {
        for(var block:blocks(sse))if(block.path("type").asText().equals("TOOL_RESULT")) {
            var tool=JSON.readTree(block.path("data").asText());
            if(tool.path("toolName").asText().equals("callingExplorerSubAgent"))return JSON.readTree(tool.path("result").asText());
        }
        throw new AssertionError("Explorer result missing");
    }

    @Test void batchStructuresAreHostVerifiedAndDoNotExecuteSql() throws Exception {
        long id=session(true);clearInvocations(COLUMNS,FOREIGN_KEYS,SQL);
        String task="explore-json:"+JSON.writeValueAsString(Map.of("tasks",List.of(
                Map.of("connectionId",connectionId,"catalogName","fixture_db","instruction","batch-read","mode","schema"))));
        var result=explorerResult(piTurn(id,task,null)).path("taskResults").get(0);
        assertThat(result.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(result.path("structures").size()).isEqualTo(2);
        for(var structure:result.path("structures")) {
            assertThat(structure.path("target").path("catalogName").asText()).isEqualTo("fixture_db");
            assertThat(structure.path("columns").get(0).path("name").asText()).isEqualTo("id");
            assertThat(structure.path("columns").get(0).path("primaryKey").asBoolean()).isTrue();
            assertThat(structure.path("foreignKeys").path("available").asBoolean()).isTrue();
        }
        verify(COLUMNS).listColumns(new DbContext(connectionId,"fixture_db",null),"safe_table");
        verify(COLUMNS).listColumns(new DbContext(connectionId,"fixture_db",null),"second_table");
        verifyNoInteractions(SQL);
    }

    @Test void batchMetadataValidatesAllNamesBeforeReadingAnything() throws Exception {
        for(var names:List.of(List.of("safe_table","bad.table"),List.of("safe_table","safe_table"),
                List.of("a","b","c","d","e"),List.of())) {
            clearInvocations(COLUMNS,FOREIGN_KEYS,SQL);
            String body=piTurn(session(true),"batch-json:"+JSON.writeValueAsString(Map.of("tableNames",names)),null);
            assertThat(body).contains("LOCAL_REQUEST_INVALID");
            verifyNoInteractions(COLUMNS,FOREIGN_KEYS,SQL);
        }
    }

    @Test void schemaProfileRejectsAWorkerAttemptToQueryBusinessData() throws Exception {
        clearInvocations(SQL);
        String task="explore-json:"+JSON.writeValueAsString(Map.of("tasks",List.of(
                Map.of("connectionId",connectionId,"catalogName","fixture_db","instruction","query","mode","schema"))));
        var result=explorerResult(piTurn(session(true),task,null)).path("taskResults").get(0);
        assertThat(result.path("status").asText()).isEqualTo("ERROR");
        assertThat(result.path("errorMessage").asText()).isEqualTo("LOCAL_SCOPE_FORBIDDEN");
        verifyNoInteractions(SQL);
    }

    @Test void anEmptyColumnResultCannotBecomeVerifiedTableEvidence() throws Exception {
        when(COLUMNS.listColumns(any(),eq("missing_table"))).thenReturn(List.of());
        clearInvocations(COLUMNS,FOREIGN_KEYS,SQL);
        String body=piTurn(session(true),"batch-json:{\"tableNames\":[\"missing_table\"]}",null);
        assertThat(body).contains("LOCAL_TABLE_METADATA_UNAVAILABLE");
        verifyNoInteractions(FOREIGN_KEYS,SQL);
    }
    static String exploration(String instruction,int timeout) throws Exception {
        return "explore-json:"+JSON.writeValueAsString(Map.of("tasks",List.of(Map.of("connectionId",connectionId,"instruction",instruction,"timeoutSeconds",timeout))));
    }

    @Test void piExplorerUsesItsOwnModelAndIsolatedTasksThenReturnsVerifiedStructuredResults(CapturedOutput output) throws Exception {
        long id=session(true);clearInvocations(TABLES);
        String task="explore-json:"+JSON.writeValueAsString(Map.of("tasks",List.of(
                Map.of("connectionId",connectionId,"catalogName","first_db","instruction","find first table"),
                Map.of("connectionId",connectionId,"catalogName","second_db","instruction","find second table"))));
        String body=piTurn(id,task,null);
        var results=explorerResult(body).path("taskResults");assertThat(results.size()).isEqualTo(2);
        for(var result:results) {
            assertThat(result.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(result.path("summaryText").asText()).contains("Explorer model=explorer-fixture-model");
            assertThat(result.path("objects").get(0).path("objectName").asText()).isEqualTo("safe_table");
        }
        assertThat(results.get(0).path("taskId").asText()).isNotEqualTo(results.get(1).path("taskId").asText());
        assertThat(results.get(0).path("objects").get(0).path("catalog").asText()).isEqualTo("first_db");
        assertThat(results.get(1).path("objects").get(0).path("catalog").asText()).isEqualTo("second_db");
        verify(TABLES).getTables(new DbContext(connectionId,"first_db",null));
        verify(TABLES).getTables(new DbContext(connectionId,"second_db",null));
        var events=blocks(body);assertThat(events.stream().filter(event->event.path("done").asBoolean())).hasSize(1);
        assertThat(events.stream().filter(event->event.path("type").asText().equals("SUB_AGENT_START"))).hasSize(2);
        assertThat(events.stream().filter(event->event.path("type").asText().equals("SUB_AGENT_COMPLETE"))).hasSize(2);
        assertThat(events.stream().filter(event->event.path("type").asText().equals("TOOL_RESULT") && event.hasNonNull("parentToolCallId"))).hasSize(2);
        assertThat(body).contains("SUCCEEDED").doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
        assertThat(output).contains("Pi chat started:","Pi tool started:","tool=callingExplorerSubAgent",
                "Pi Explorer started:","Pi Explorer model selected:","Pi Explorer tool finished:",
                "Pi Explorer finished:","Pi chat finished:","elapsedMs=");
        assertThat(output).doesNotContain("FIXTURE_SECRET_NEVER_PRINT","instruction=find first table");
        assertThat(JSON.readTree(events.getLast().path("data").asText()).path("statistics").path("modelCalls").asInt()).isEqualTo(3);
    }

    @Test void piExplorerRejectsCrossConnectionAndCrossCatalogToolTargets() throws Exception {
        for(var target:List.of(Map.of("connectionId",999999),Map.of("catalogName","unrequested_db"))) {
            clearInvocations(TABLES,SQL);
            String body=piTurn(session(true),exploration("escape:"+JSON.writeValueAsString(target),5),null);
            assertThat(explorerResult(body).path("taskResults").get(0).path("status").asText()).isEqualTo("ERROR");
            assertThat(body).contains("SUB_AGENT_ERROR","LOCAL_SCOPE_FORBIDDEN");
            verifyNoInteractions(TABLES,SQL);
        }
    }

    @Test void piExplorerRejectsInventedObjectsEvenWhenTheChildModelFinishesNormally() throws Exception {
        String body=piTurn(session(true),exploration("invent-object",5),null);
        assertThat(explorerResult(body).path("taskResults").get(0).path("status").asText()).isEqualTo("ERROR");
        assertThat(body).contains("PI_EXPLORER_RESULT_INVALID");
    }

    @Test void piExplorerReturnsHostVerifiedQueryExecutionEvidence() throws Exception {
        clearInvocations(SQL);
        String body=piTurn(session(true),exploration("query",5),null);
        var task=explorerResult(body).path("taskResults").get(0);
        assertThat(task.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(task.path("executions").size()).as(body).isEqualTo(1);
        var execution=task.path("executions").get(0);
        var captured=org.mockito.ArgumentCaptor.forClass(AgentExecuteSqlRequest.class);verify(SQL).executeSql(captured.capture());
        assertThat(execution.path("executionId").asText()).isEqualTo(captured.getValue().getExecutionId());
        assertThat(execution.path("target").path("connectionId").asLong()).isEqualTo(connectionId);
        assertThat(execution.path("target").path("catalog").asText()).isEqualTo("fixture_db");
        assertThat(execution.path("success").asBoolean()).isTrue();
        assertThat(execution.path("statementState").asText()).isEqualTo("EXECUTED");
        assertThat(execution.path("truncated").asBoolean()).isFalse();
        assertThat(blocks(body).stream().filter(block->block.path("done").asBoolean())).hasSize(1);
    }

    @Test void aChildWorkerCrashIsReportedToTheParentWithoutChangingEngines() throws Exception {
        long id=session(true);
        String body=piTurn(id,exploration("crash",5),null);
        assertThat(explorerResult(body).path("taskResults").get(0).path("status").asText()).isEqualTo("ERROR");
        assertThat(body).contains("PI_WORKER_EXIT");
        assertThat(piTurn(id,"hello after child failure",null)).contains("pong-turn2","SUCCEEDED");
    }

    @Test void piExplorerValidatesAllTasksBeforeStartingAnyChild() throws Exception {
        clearInvocations(TABLES,SQL);
        for(var task:List.of(
                Map.of("tasks",List.of(Map.of("connectionId",connectionId,"instruction","find tables"),Map.of("connectionId",1.5,"instruction","invalid"))),
                Map.of("tasks",List.of(Map.of("connectionId",connectionId,"instruction","find","timeoutSeconds",90))),
                Map.of("tasks",List.of(Map.of("connectionId",connectionId,"instruction","find","extra","ignored?"))))) {
            String body=piTurn(session(true),"explore-json:"+JSON.writeValueAsString(task),null);
            assertThat(body).contains("LOCAL_REQUEST_INVALID").doesNotContain("SUB_AGENT_START");
        }
        verifyNoInteractions(TABLES,SQL);
    }

    @Test void piExplorerTimeoutCancelsOnlyTheChildQueryAndReturnsAnErrorToTheParent() throws Exception {
        clearInvocations(SQL);QUERY_STARTED.set(new CountDownLatch(1));QUERY_RELEASE.set(new CountDownLatch(1));
        try {
            String body=piTurn(session(true),exploration("wait-query",1),null);
            assertThat(QUERY_STARTED.get().getCount()).isZero();assertThat(QUERY_RELEASE.get().getCount()).isZero();
            assertThat(explorerResult(body).path("taskResults").get(0).path("errorMessage").asText()).isEqualTo("PI_EXPLORER_TIMEOUT");
            verify(SQL).cancelExecution(eq(new DbContext(connectionId,"fixture_db",null)),anyString());
            assertThat(blocks(body).stream().filter(block->block.path("done").asBoolean())).hasSize(1);
        } finally { QUERY_RELEASE.get().countDown(); }
    }

    @Test void cancellingTheParentStopsTheExplorerQueryAndEmitsOnlyOneCancelledDone() throws Exception {
        long id=session(true);clearInvocations(SQL);QUERY_STARTED.set(new CountDownLatch(1));QUERY_RELEASE.set(new CountDownLatch(1));
        try {
            var running=HTTP.sendAsync(request("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"message",exploration("wait-query",30),"modelConfigId",modelId)),HttpResponse.BodyHandlers.ofString());
            assertThat(QUERY_STARTED.get().await(10,TimeUnit.SECONDS)).isTrue();
            call("POST","/api/local/conversations/"+id+"/cancel",null,true);
            String body=running.get(10,TimeUnit.SECONDS).body();
            verify(SQL,timeout(3000)).cancelExecution(eq(new DbContext(connectionId,"fixture_db",null)),anyString());
            var done=blocks(body).stream().filter(block->block.path("done").asBoolean()).toList();assertThat(done).hasSize(1);
            assertThat(done.getFirst().path("data").asText()).contains("CANCELLED");
        } finally { QUERY_RELEASE.get().countDown(); }
    }

    @Test void discoveryStructureAndQueryFlowRetainsExecutionFactsAndOneDone() throws Exception {
        long id=session(true); clearInvocations(SQL,TABLES,COLUMNS);
        String body=piTurn(id,"discover-query",null);
        verify(TABLES).getTables(new DbContext(connectionId,"fixture_db",null));
        verify(COLUMNS).listColumns(new DbContext(connectionId,"fixture_db",null),"safe_table");
        var captured=org.mockito.ArgumentCaptor.forClass(AgentExecuteSqlRequest.class); verify(SQL).executeSql(captured.capture());
        var request=captured.getValue();
        assertThat(request.getTimeoutMs()).isEqualTo(15000); assertThat(request.getMaxRows()).isEqualTo(200);
        assertThat(request.getConversationId()).isEqualTo(id); assertThat(request.getConnectionId()).isEqualTo(connectionId);
        assertThat(request.getCatalog()).isEqualTo("fixture_db");
        var tool=queryTool(body); assertThat(tool.path("error").asBoolean()).isFalse();
        var result=JSON.readTree(tool.path("result").asText());
        assertThat(result.path("executionId").asText()).isEqualTo(request.getExecutionId());
        assertThat(result.path("target").path("connectionId").asLong()).isEqualTo(connectionId);
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("statementState").asText()).isEqualTo("EXECUTED");
        assertThat(result.path("result").path("rows").get(0).get(0).asInt()).isEqualTo(7);
        assertThat(body).contains("query-result:false:","SUCCEEDED");
        assertThat(blocks(body).stream().filter(b -> b.path("done").asBoolean()).count()).isEqualTo(1);
    }

    @Test void unsafeQueriesAndMalformedArgumentsNeverReachExecution() throws Exception {
        long id=session(true); clearInvocations(SQL);
        for(String query:List.of("DELETE FROM safe_table","CREATE TABLE x(id INT)","SELECT 1; SELECT 2",
                "SELECT * FROM safe_table FOR UPDATE","SELECT SLEEP(1)","SELECT seq.NEXTVAL FROM DUAL")) {
            var tool=queryTool(piTurn(id,"query-json:"+JSON.writeValueAsString(Map.of("query",query)),null));
            assertThat(tool.path("error").asBoolean()).isTrue();
            assertThat(tool.path("result").asText()).contains("LOCAL_FEATURE_NOT_SUPPORTED");
        }
        for(Object invalid:List.of("not-an-id",1.5,0,9007199254740992L)) {
            var tool=queryTool(piTurn(id,"query-json:"+JSON.writeValueAsString(Map.of("query","SELECT 1","connectionId",invalid)),null));
            assertThat(tool.path("error").asBoolean()).isTrue();
            assertThat(tool.path("result").asText()).contains("LOCAL_REQUEST_INVALID");
        }
        for(Object invalid:List.of(Map.of("query",7),Map.of("query","SELECT 1","extra","ignored"),List.of("SELECT 1"))) {
            var tool=queryTool(piTurn(id,"query-json:"+JSON.writeValueAsString(invalid),null));
            assertThat(tool.path("result").asText()).contains("LOCAL_REQUEST_INVALID");
        }
        verify(SQL,never()).executeSql(any());
    }

    @Test void queryWithoutAConnectionIsRejectedWithoutExecution() throws Exception {
        long id=session(false); clearInvocations(SQL);
        var tool=queryTool(piTurn(id,"query-json:{\"query\":\"SELECT 1\"}",null));
        assertThat(tool.path("error").asBoolean()).isTrue();
        assertThat(tool.path("result").asText()).contains("LOCAL_SCOPE_FORBIDDEN");
        verify(SQL,never()).executeSql(any());
    }

    @Test void referencedConnectionAndSchemaOverrideTheQueryDefault() throws Exception {
        var created=call("POST","/api/connections/create",Map.of("name","Query archive","dbType","dm","host","example.invalid","port",5236,
                "username","fixture","driverJarPath",root.resolve("fixture-driver.jar").toString()),true);
        long other=JSON.readTree(created.body()).path("data").path("id").asLong();
        long id=session(true); clearInvocations(SQL);
        var mention=Map.<String,Object>of("token","@orders","connectionId",other,"schemaName","ARCHIVE","objectType","TABLE","objectName","orders");
        var tool=queryTool(piTurn(id,"query-mention @orders",List.of(mention)));
        assertThat(tool.path("error").asBoolean()).isFalse();
        var captured=org.mockito.ArgumentCaptor.forClass(AgentExecuteSqlRequest.class); verify(SQL).executeSql(captured.capture());
        assertThat(captured.getValue().getConnectionId()).isEqualTo(other);
        assertThat(captured.getValue().getCatalog()).isNull(); assertThat(captured.getValue().getSchema()).isEqualTo("ARCHIVE");
    }

    @Test void queryTimeoutIsAToolErrorAndRetainsUnknownTransactionState() throws Exception {
        var tool=queryTool(piTurn(session(true),"query-json:{\"query\":\"SELECT 1 /* timeout-query */\"}",null));
        assertThat(tool.path("error").asBoolean()).isTrue();
        var result=JSON.readTree(tool.path("result").asText());
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("statementState").asText()).isEqualTo("TIMED_OUT");
        assertThat(result.path("transactionOutcome").asText()).isEqualTo("UNKNOWN");
    }

    @Test void changedConnectionVersionPreventsTheQueryFromExecuting() throws Exception {
        var input=new LinkedHashMap<String,Object>(Map.of("name","Versioned query db","dbType","mysql","host","example.invalid","port",3306,
                "username","fixture","driverJarPath",root.resolve("fixture-driver.jar").toString()));
        var created=JSON.readTree(call("POST","/api/connections/create",input,true).body()).path("data");
        long connection=created.path("id").asLong();
        long id=JSON.readTree(call("POST","/api/local/conversations",Map.of("connectionId",connection,"catalogName","fixture_db"),true).body())
                .path("data").path("conversationId").asLong();
        input.put("connectionId",connection); input.put("expectedVersion",created.path("version").asLong()); input.put("name","Changed query db");
        call("PUT","/api/connections",input,true); clearInvocations(SQL);
        var tool=queryTool(piTurn(id,"query-json:{\"query\":\"SELECT 1\"}",null));
        assertThat(tool.path("error").asBoolean()).isTrue();
        assertThat(tool.path("result").asText()).contains("CONNECTION_REVISION_CONFLICT");
        verify(SQL,never()).executeSql(any());
    }

    @Test void databaseAndContentTruncationStayVisibleWithExecutionIdentity() throws Exception {
        long id=session(true);
        for(String marker:List.of("truncated-query","large-query")) {
            var tool=queryTool(piTurn(id,"query-json:"+JSON.writeValueAsString(Map.of("query","SELECT 1 /* "+marker+" */")),null));
            String text=tool.path("result").asText(); assertThat(text.length()).isLessThanOrEqualTo(12000);
            var result=JSON.readTree(text);
            assertThat(result.path("success").asBoolean()).isTrue(); assertThat(result.path("truncated").asBoolean()).isTrue();
            assertThat(result.path("executionId").asText()).isNotBlank();
            assertThat(result.path("target").path("connectionId").asLong()).isEqualTo(connectionId);
            assertThat(result.path("statementState").asText()).isEqualTo("EXECUTED");
        }
    }

    @Test void queryCancellationReachesTheSqlExecutionAndDropsItsLateResult() throws Exception {
        long id=session(true); clearInvocations(SQL);
        QUERY_STARTED.set(new CountDownLatch(1)); QUERY_RELEASE.set(new CountDownLatch(1));
        var pending=CompletableFuture.supplyAsync(() -> {
            try { return piTurn(id,"query-json:{\"query\":\"SELECT 1 /* wait-query */\"}",null); }
            catch(Exception e) { throw new RuntimeException(e); }
        });
        try {
            assertThat(QUERY_STARTED.get().await(10,TimeUnit.SECONDS)).isTrue();
            call("POST","/api/local/conversations/"+id+"/cancel",null,true);
            String body=pending.get(10,TimeUnit.SECONDS);
            assertThat(body).contains("CANCELLED").doesNotContain("query-result:false:");
            verify(SQL,timeout(3000)).cancelExecution(eq(new DbContext(connectionId,"fixture_db",null)),anyString());
            assertThat(blocks(body).stream().filter(b -> b.path("done").asBoolean()).count()).isEqualTo(1);
        } finally { QUERY_RELEASE.get().countDown(); }
    }

    @Test void workerCrashDuringQueryCancelsSqlAndTheNextTurnRebuildsTheWorker() throws Exception {
        long id=session(true); clearInvocations(SQL);
        QUERY_STARTED.set(new CountDownLatch(1)); QUERY_RELEASE.set(new CountDownLatch(1));
        try {
            String body=piTurn(id,"query-json:{\"query\":\"SELECT 1 /* wait-query crash-query */\"}",null);
            assertThat(QUERY_STARTED.get().getCount()).isZero();
            assertThat(body).contains("FAILED","PI_WORKER_EXIT").doesNotContain("query-result:false:");
            assertThat(QUERY_RELEASE.get().getCount()).isZero();
            verify(SQL).cancelExecution(eq(new DbContext(connectionId,"fixture_db",null)),anyString());
            assertThat(piTurn(id,"hello after query crash",null)).contains("pong-turn1","SUCCEEDED");
        } finally { QUERY_RELEASE.get().countDown(); }
    }

    @Test void capabilityIsReadyAndCredentialNeverLeaks() throws Exception {
        var response=call("GET","/api/runtime/capabilities",null,true);
        var feature=JSON.readTree(response.body()).path("data").path("features").path("piAssistant");
        assertThat(feature.path("state").asText()).isEqualTo("READY");
        assertThat(response.body()).doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
    }

    @Test void simpleTurnStreamsTextAndExactlyOneTerminalBlock() throws Exception {
        long id=session(false);
        String body=piTurn(id,"hello pi",null);
        assertThat(body).contains("pong-turn1","SUCCEEDED","engine");
        assertThat(body).contains("statistics").contains("modelCalls");
        assertThat(body).doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
    }

    @Test void nonJsonWorkerOutputFailsTheRunWithoutASecondDone() throws Exception {
        long id=session(false);
        String body=piTurn(id,"invalid-stdout",null);
        assertThat(body).contains("FAILED","PI_PROTOCOL_ERROR");
        assertThat(blocks(body).stream().filter(block->block.path("done").asBoolean())).hasSize(1);
        assertThat(piTurn(id,"hello after protocol noise",null)).contains("pong-turn1","SUCCEEDED");
    }

    @Test void workerStderrCannotCopyCredentialsIntoApplicationLogs(CapturedOutput output) throws Exception {
        String body=piTurn(session(false),"stderr-secret",null);
        assertThat(body).contains("SUCCEEDED").doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
        assertThat(output).doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
    }

    @Test void modelErrorDiagnosticsAreBoundedAndNeverCopyProviderText(CapturedOutput output) throws Exception {
        for(String input:List.of("model-error-diagnostics","model-error-invalid-diagnostics")) {
            String body=piTurn(session(false),input,null);
            var done=blocks(body).stream().filter(block->block.path("done").asBoolean()).toList();
            assertThat(done).hasSize(1);
            var result=JSON.readTree(done.getFirst().path("data").asText());
            assertThat(result.path("status").asText()).isEqualTo("FAILED");
            assertThat(result.path("errorCode").asText()).isEqualTo("PI_MODEL_ERROR");
            var info=result.path("modelError");
            assertThat(info.path("kind").asText()).isEqualTo(input.equals("model-error-diagnostics")?"AUTH":"UNKNOWN");
            if(input.equals("model-error-diagnostics")) {
                assertThat(info.path("httpStatus").asInt()).isEqualTo(401);
                assertThat(info.path("elapsedMs").asLong()).isEqualTo(123);
            } else assertThat(info.size()).isEqualTo(1);
            assertThat(body).doesNotContain("FIXTURE_SECRET_NEVER_PRINT","requestId","detail");
        }
        assertThat(output).doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
    }

    @Test void unsupportedModelIsRejectedBeforeStartingTheWorker() throws Exception {
        var setup=new LinkedHashMap<String,Object>();
        setup.put("name","Unsupported Pi model"); setup.put("provider","CUSTOM"); setup.put("apiStyle","OPENAI_RESPONSES");
        setup.put("endpoint","https://fixture.example.invalid/v1/"); setup.put("compatibilityProfile","openai-responses-v1");
        setup.put("apiKey","FIXTURE_SECRET_NEVER_PRINT"); setup.put("modelId","responses-fixture"); setup.put("capabilityProfile","responses-tools-v1");
        setup.put("defaultOptions",Map.of("thinkingMode","DISABLED","maxOutputTokens",2048));
        setup.put("maxContextTokens",32768); setup.put("memoryThreshold",4000);
        setup.put("enabled",true); setup.put("chatVisible",true); setup.put("makeDefault",false);
        var saved=call("POST","/api/ai/model-setups",setup,true);
        String unsupportedId=JSON.readTree(saved.body()).path("data").path("model").path("id").asText();
        long id=session(false);
        var rejected=call("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"message","hello","modelConfigId",unsupportedId),false);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(rejected.body()).contains("PI_UNSUPPORTED_API_STYLE").doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
        assertThat(piTurn(id,"hello with compatible model",null)).contains("pong-turn1","SUCCEEDED");
        var binding=JSON.readTree(call("GET","/api/ai/model-bindings/EXPLORER",null,true).body()).path("data");
        call("PUT","/api/ai/model-bindings/EXPLORER",Map.of("expectedVersion",binding.path("version").asLong(),"modelConfigId",unsupportedId),true);
        try {
            clearInvocations(TABLES);
            String body=piTurn(session(true),exploration("find tables",5),null);
            assertThat(explorerResult(body).path("taskResults").get(0).path("status").asText()).isEqualTo("ERROR");
            assertThat(body).contains("PI_UNSUPPORTED_API_STYLE");verifyNoInteractions(TABLES);
        } finally {
            call("PUT","/api/ai/model-bindings/EXPLORER",Map.of("expectedVersion",binding.path("version").asLong()+1,"modelConfigId",binding.path("modelConfigId").asText()),true);
        }
    }

    @Test void workbenchSnapshotsReachTheWorkerWithTheirOwnValidatedTarget() throws Exception {
        var created=call("POST","/api/connections/create",Map.of("name","Workbench DM","dbType","dm","host","example.invalid","port",5236,
                "username","fixture","driverJarPath",root.resolve("fixture-driver.jar").toString()),true);
        long other=JSON.readTree(created.body()).path("data").path("id").asLong();
        long id=session(true);
        var workbench=Map.of("documentId","query-b","documentName","Query B","documentVersion",3,"sql","SELECT 2;",
                "selected",true,"truncated",false,"target",Map.of("connectionId",other,"catalogName","discarded","schemaName","ARCHIVE"));
        String body=call("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"modelConfigId",modelId,
                "message","echo-workbench","workbenchContext",workbench),true).body();
        var text=blocks(body).stream().filter(block->block.path("type").asText().equals("TEXT"))
                .map(block->block.path("data").asText()).collect(java.util.stream.Collectors.joining());
        var actual=JSON.readTree(text);
        assertThat(actual.path("sql").asText()).isEqualTo("SELECT 2;");
        assertThat(actual.path("documentVersion").asInt()).isEqualTo(3);
        assertThat(actual.path("target").path("connectionId").asLong()).isEqualTo(other);
        assertThat(actual.path("target").path("schemaName").asText()).isEqualTo("ARCHIVE");
        assertThat(actual.path("target").path("catalogName").isNull()).isTrue();
        assertThat(call("GET","/api/local/conversations/"+id,null,true).body()).contains("fixture_db").doesNotContain("ARCHIVE");
        assertThat(piTurn(id,"echo-workbench",null)).contains("null","SUCCEEDED");
        var oversized=new LinkedHashMap<String,Object>(workbench); oversized.put("sql","x".repeat(16001));
        assertThat(call("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"modelConfigId",modelId,
                "message","echo-workbench","workbenchContext",oversized),false).statusCode()).isEqualTo(400);
    }

    @Test void executionSnapshotsPreserveOriginalTargetAndRejectOversizedSamples() throws Exception {
        var created=call("POST","/api/connections/create",Map.of("name","Execution DM","dbType","dm","host","example.invalid","port",5236,
                "username","fixture","driverJarPath",root.resolve("fixture-driver.jar").toString()),true);
        long other=JSON.readTree(created.body()).path("data").path("id").asLong(),id=session(true);
        var result=new LinkedHashMap<String,Object>(Map.of("success",false,"statementState","FAILED","transactionOutcome","ROLLED_BACK",
                "batchState","ROLLED_BACK","errorMessage","missing column","sqlState","42S22","headers",List.of(),"rows",List.of(),"returnedRows",0));
        var reference=new LinkedHashMap<String,Object>(Map.of("executionId","execution-a","statementIndex",1,"sql","SELECT missing;",
                "documentId","query-a","documentName","Query A","documentVersion",2,"truncated",false,
                "target",Map.of("connectionId",other,"catalogName","discarded","schemaName","ARCHIVE"),"result",result));
        var request=new LinkedHashMap<String,Object>(Map.of("conversationId",id,"modelConfigId",modelId,
                "message","echo-execution","executionReference",reference));
        // Invalid payloads are rejected before a run starts, including nested cell bounds.
        for (var rows:List.of(List.of(List.of("x".repeat(129))),java.util.Collections.nCopies(6,List.of("x")),
                List.of(java.util.Collections.nCopies(9,"x")) )) {
            result.put("rows",rows);
            assertThat(call("POST","/api/local/pi/chat/stream",request,false).statusCode()).isEqualTo(400);
        }
        result.put("rows",List.of());
        String body=call("POST","/api/local/pi/chat/stream",request,true).body();
        var text=blocks(body).stream().filter(block->block.path("type").asText().equals("TEXT"))
                .map(block->block.path("data").asText()).collect(java.util.stream.Collectors.joining());
        var actual=JSON.readTree(text);
        assertThat(actual.path("executionId").asText()).isEqualTo("execution-a");
        assertThat(actual.path("statementIndex").asInt()).isEqualTo(1);
        assertThat(actual.path("sql").asText()).isEqualTo("SELECT missing;");
        assertThat(actual.path("documentVersion").asInt()).isEqualTo(2);
        assertThat(actual.path("result").path("sqlState").asText()).isEqualTo("42S22");
        assertThat(actual.path("result").path("transactionOutcome").asText()).isEqualTo("ROLLED_BACK");
        assertThat(actual.path("target").path("connectionId").asLong()).isEqualTo(other);
        assertThat(actual.path("target").path("schemaName").asText()).isEqualTo("ARCHIVE");
        assertThat(actual.path("target").path("catalogName").isNull()).isTrue();
        assertThat(body).contains("SUCCEEDED").doesNotContain("TOOL_CALL");
        assertThat(piTurn(id,"echo-execution",null)).contains("null","SUCCEEDED");
        reference.put("target",Map.of("schemaName","ARCHIVE"));
        assertThat(call("POST","/api/local/pi/chat/stream",request,false).statusCode()).isEqualTo(400);
    }

    @Test void mentionIdentityReachesDescribeTableWithFullTarget() throws Exception {
        long id=session(true); clearInvocations(COLUMNS);
        var mention=Map.<String,Object>of("token","@orders","connectionId",connectionId,"connectionName","spoof",
                "catalogName","fixture_db","objectType","TABLE","objectName","orders");
        String body=piTurn(id,"解释 @orders 的结构",List.of(mention));
        assertThat(body).contains("TOOL_CALL","TOOL_RESULT","SUCCEEDED");
        verify(COLUMNS).listColumns(new DbContext(connectionId,"fixture_db",null),"orders");
        // The resolved target identity is part of the tool result handed back to the worker.
        assertThat(body).contains("\\\"connectionId\\\":"+connectionId).contains("tool-result:false:");
        assertThat(body).doesNotContain("spoof");
    }

    @Test void secondConnectionSameTableNameUsesItsOwnIdentity() throws Exception {
        Path driver=root.resolve("fixture-driver.jar");
        var created=call("POST","/api/connections/create",Map.of("name","Other db","dbType","dm","host","example.invalid","port",5236,
                "username","fixture","driverJarPath",driver.toString()),true);
        long other=JSON.readTree(created.body()).path("data").path("id").asLong();
        long id=session(true); clearInvocations(COLUMNS);
        var mention=Map.<String,Object>of("token","@orders","connectionId",other,"connectionName","other",
                "schemaName","ARCHIVE","objectType","TABLE","objectName","orders");
        String body=piTurn(id,"@orders 的字段",List.of(mention));
        assertThat(body).contains("SUCCEEDED");
        // dm does not support database/catalog; schema must survive normalization, default target must not override it.
        verify(COLUMNS).listColumns(new DbContext(other,null,"ARCHIVE"),"orders");
        verify(COLUMNS,never()).listColumns(eq(new DbContext(connectionId,"fixture_db",null)),any());
    }

    @Test void discoveryFlowListsThenDescribesUnderTheDefaultTarget() throws Exception {
        long id=session(true); clearInvocations(TABLES,COLUMNS);
        String body=piTurn(id,"discover tables please",null);
        assertThat(body).contains("SUCCEEDED","TOOL_CALL","TOOL_RESULT");
        verify(TABLES).getTables(new DbContext(connectionId,"fixture_db",null));
        verify(COLUMNS).listColumns(new DbContext(connectionId,"fixture_db",null),"safe_table");
    }

    @Test void unknownToolReturnsErrorToWorkerAndRunCompletes() throws Exception {
        long id=session(true); clearInvocations(TABLES);
        String body=piTurn(id,"fail-tool now",null);
        assertThat(body).contains("TOOL_RESULT","LOCAL_REQUEST_INVALID","tool-error-seen:true","SUCCEEDED");
        verify(TABLES,never()).getTables(any());
    }

    @Test void multiTurnReusesTheSameWorkerSession() throws Exception {
        long id=session(false);
        assertThat(piTurn(id,"first",null)).contains("pong-turn1","SUCCEEDED");
        assertThat(piTurn(id,"second",null)).contains("pong-turn2","SUCCEEDED");
    }

    @Test void piCompactionIsRejectedWithoutChangingWorkerHistory() throws Exception {
        long id=session(false);
        assertThat(piTurn(id,"first",null)).contains("pong-turn1","SUCCEEDED");
        var compact=call("POST","/api/local/conversations/"+id+"/compact",null,false);
        assertThat(compact.statusCode()).isEqualTo(409);
        assertThat(compact.body()).contains("PI_COMPACTION_NOT_SUPPORTED").doesNotContain("\"compacted\":false");
        assertThat(piTurn(id,"second",null)).contains("pong-turn2","SUCCEEDED");
    }

    @Test void piConversationCannotBeContinuedThroughJavaEntry() throws Exception {
        long id=session(false);
        assertThat(piTurn(id,"first",null)).contains("pong-turn1","SUCCEEDED");
        var javaTurn=call("POST","/api/local/chat/stream",Map.of("conversationId",id,"message","wrong engine","modelConfigId",modelId),false);
        assertThat(javaTurn.statusCode()).isEqualTo(409);
        assertThat(javaTurn.body()).contains("LOCAL_SESSION_ENGINE_MISMATCH");
        assertThat(piTurn(id,"second",null)).contains("pong-turn2","SUCCEEDED");
    }

    @Test void cancelWhileWaitingForToolSettlesCancelled() throws Exception {
        long id=session(false);
        var pending=CompletableFuture.supplyAsync(() -> {
            try { return piTurn(id,"wait-for-cancel",null); } catch (Exception e) { throw new RuntimeException(e); }
        });
        long deadline=System.currentTimeMillis()+15000;
        String body=null;
        // Give the run a moment to reach the worker, then cancel through the existing endpoint.
        Thread.sleep(800);
        var cancel=call("POST","/api/local/conversations/"+id+"/cancel",null,true);
        assertThat(cancel.body()).contains("CANCEL_REQUESTED");
        body=pending.get(20,TimeUnit.SECONDS);
        assertThat(body).contains("CANCELLED");
        assertThat(body).doesNotContain("SUCCEEDED");
    }

    @Test void workerCrashFailsTheRunAndTheNextRunRestarts() throws Exception {
        long id=session(false);
        String crashed=piTurn(id,"crash-now",null);
        assertThat(crashed).contains("FAILED","PI_WORKER_EXIT");
        String recovered=piTurn(id,"hello again",null);
        assertThat(recovered).contains("pong-turn1","SUCCEEDED");
    }

    @Test void lateEventsAfterFinishAreDropped() throws Exception {
        long id=session(false);
        String body=piTurn(id,"late-events",null);
        assertThat(body).contains("SUCCEEDED");
        assertThat(body).doesNotContain("LATE-DELTA","PI_WORKER_ERROR");
    }

    @Test void busySessionRejectsSecondRun() throws Exception {
        long id=session(false);
        var pending=CompletableFuture.supplyAsync(() -> {
            try { return piTurn(id,"wait-for-cancel",null); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(800);
        var second=call("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"message","hello","modelConfigId",modelId),false);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("LOCAL_SESSION_BUSY");
        call("POST","/api/local/conversations/"+id+"/cancel",null,true);
        assertThat(pending.get(20,TimeUnit.SECONDS)).contains("CANCELLED");
    }
}
