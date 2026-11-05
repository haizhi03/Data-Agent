package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.FinishReason;
import edu.zsc.ai.DataAgentApplication;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.domain.service.db.impl.SqlExecutionServiceImpl;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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

/** Real local HTTP/SSE and production model guards; deterministic SDK fixtures, no vendor or business DB calls. */
@ExtendWith(OutputCaptureExtension.class)
class LocalAssistantHttpTest {
    @TempDir static Path root;
    static ServletWebServerApplicationContext context;
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static String base, token, runtime, modelId;
    static long connectionId;
    static final AtomicReference<CountDownLatch> waiting=new AtomicReference<>(new CountDownLatch(1));
    static final AtomicReference<ChatRequest> lastRequest=new AtomicReference<>();
    static final TableService TABLES=mock(TableService.class);
    static final edu.zsc.ai.domain.service.db.DatabaseService DATABASES=mock(edu.zsc.ai.domain.service.db.DatabaseService.class);
    static final edu.zsc.ai.domain.service.db.SchemaService SCHEMAS=mock(edu.zsc.ai.domain.service.db.SchemaService.class);
    static final SqlExecutionServiceImpl SQL=mock(SqlExecutionServiceImpl.class);

    static final LocalWriteExecutor WRITES=mock(LocalWriteExecutor.class);
    static final AtomicInteger writeCalls=new AtomicInteger();
    static final AtomicReference<CountDownLatch> writeStarted=new AtomicReference<>(new CountDownLatch(1));
    static final AtomicReference<CountDownLatch> writeRelease=new AtomicReference<>(new CountDownLatch(1));
    @Configuration(proxyBeanMethods=false) static class Fixture {
        @Bean @Primary edu.zsc.ai.domain.service.db.DatabaseService fixtureDatabases() {
            when(DATABASES.getDatabases(any())).thenReturn(List.of("fixture_db")); return DATABASES;
        }
        @Bean @Primary edu.zsc.ai.domain.service.db.SchemaService fixtureSchemas() {
            when(SCHEMAS.listSchemas(any(),any())).thenReturn(List.of("APP")); return SCHEMAS;
        }
        @Bean @Primary LocalWriteExecutor fixtureWrites() {
            when(WRITES.execute(any())).thenAnswer(invocation -> {
                LocalWriteProposal.Operation operation=invocation.getArgument(0);writeCalls.incrementAndGet();
                String statement=operation.proposal.statements().getFirst().sql();
                if (statement.contains("wait")) { writeStarted.get().countDown();writeRelease.get().await(5,TimeUnit.SECONDS); }
                if (statement.contains("unknown")) return new LocalWriteProposal.Result("UNKNOWN","UNKNOWN",null,"LOCAL_WRITE_OUTCOME_UNKNOWN",1);
                if (operation.cancelRequested.get()) return new LocalWriteProposal.Result("CANCELLED","ROLLED_BACK",null,null,1);
                return new LocalWriteProposal.Result("SUCCEEDED","COMMITTED",1,null,1);
            });return WRITES;
        }
        @Bean @Primary ModelConnectionProbe fixtureConnectionProbe() { return new ModelConnectionProbe() { @Override public void test(ResolvedModelConfig config,String secret) { } }; }
        @Bean @Primary TableService fixtureTables() { when(TABLES.getTables(any())).thenReturn(List.of("safe_table")); return TABLES; }
        @Bean @Primary SqlExecutionServiceImpl fixtureSql() {
            var result=new ExecuteSqlResponse(); result.setSuccess(true); result.setQuery(true);
            result.setHeaders(List.of("ROW_COUNT")); result.setRows(List.of(List.of(7))); when(SQL.executeSql(any())).thenReturn(result); return SQL;
        }
        @Bean @Primary ModelClientRegistry fixtureClients(ModelConfigResolver resolver, CredentialService credentials) {
            ModelClientFactory factory=new ModelClientFactory() {
                public boolean supports(ModelApiStyle style) { return true; }
                public ModelClientBundle create(ResolvedModelConfig config) {
                    ChatModel chat=new ChatModel() { @Override public ChatResponse chat(ChatRequest request) { return response(AiMessage.from("summary: retained user goal and verified tool facts"),false); } };
                    StreamingChatModel stream=new StreamingChatModel() {
                        @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                            lastRequest.set(request);
                            var last=request.messages().getLast();
                            if (last instanceof ToolExecutionResultMessage result) {
                                handler.onPartialResponse("tool result: "+result.text()); handler.onCompleteResponse(response(AiMessage.from("tool complete"),false)); return;
                            }
                            String text=((UserMessage)last).singleText();
                            var turnContext=edu.zsc.ai.domain.service.agent.ChatTurnContext.decode(text);
                            if(turnContext!=null) text=turnContext.message();
                            if (text.equals("wait")) {
                                var run=AgentRequestContext.get().getModelRunSnapshot();
                                run.track(() -> handler.onError(new ModelIntegrationException("AGENT_RUN_CANCELLED",org.springframework.http.HttpStatus.CONFLICT)));
                                waiting.get().countDown(); return;
                            }
                            if(text.startsWith("reference-tools ") && turnContext!=null) {
                                var calls=turnContext.userMentions().stream().map(m -> {
                                    var args=new LinkedHashMap<String,Object>();args.put("connectionId",m.getConnectionId());
                                    if(m.getCatalogName()!=null) args.put("catalogName",m.getCatalogName());
                                    if(m.getSchemaName()!=null) args.put("schemaName",m.getSchemaName());
                                    return ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name("list_tables")
                                            .arguments(edu.zsc.ai.util.JsonUtil.object2json(args)).build();
                                }).toList();
                                handler.onCompleteResponse(response(AiMessage.from(calls),true));return;
                            }
                            String name=text.equals("tool")?"list_tables":text.equals("unknown")?"delete_everything":null;
                            String arguments="{}";
                            if (text.equals("sources")) name="list_connections";
                            for(String tool:List.of("list_namespaces","list_tables")) if(text.startsWith(tool+":")) {
                                name=tool;arguments=text.substring(tool.length()+1);
                            }
                            if (text.equals("query") || text.equals("write")) {
                                name="query_database"; arguments=text.equals("query")?"{\"query\":\"SELECT COUNT(*) FROM safe_table\"}":"{\"query\":\"DELETE FROM safe_table\"}";
                            }
                            if (text.startsWith("propose")) {
                                name="propose_write"; arguments="{\"statement\":\"UPDATE safe_table SET name='"+text+"  literal' WHERE id=1\"}";
                            }
                            if (name!=null) {
                                handler.onCompleteResponse(response(AiMessage.from(ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments(arguments).build()),true)); return;
                            }
                            String answer=request.messages().stream().anyMatch(m -> m instanceof UserMessage u && u.singleText().contains("MEMORY_SENTINEL"))?"remembered MEMORY_SENTINEL":"hello";
                            if (text.equals("burst")) {
                                for (int index=0;index<2000;index++) handler.onPartialResponse("x");
                                handler.onCompleteResponse(response(AiMessage.from("x".repeat(2000)),false)); return;
                            }
                            handler.onPartialResponse(answer); handler.onCompleteResponse(response(AiMessage.from(answer),false));
                        }
                    };
                    return new ModelClientBundle(chat,stream);
                }
            };
            return new ModelClientRegistry(List.of(factory),resolver,credentials);
        }
    }
    static ChatResponse response(AiMessage message,boolean tool) { return ChatResponse.builder().aiMessage(message).finishReason(tool?FinishReason.TOOL_EXECUTION:FinishReason.STOP).build(); }
    @BeforeAll static void start() throws Exception {
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/vc05-test-classpath.txt"),System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(DataAgentApplication.class,Fixture.class).run(
                "--app.runtime.mode=local","--server.port=0","--spring.main.banner-mode=off","--app.local.data-directory="+root);
        base="http://127.0.0.1:"+context.getWebServer().getPort(); var identity=context.getBean(LocalRuntimeIdentity.class);
        token=identity.transportTokenForHost(); runtime=identity.runtimeInstanceId().toString();
        var setup=new LinkedHashMap<String,Object>();
        setup.put("name","Fixture"); setup.put("provider","CUSTOM"); setup.put("apiStyle","OPENAI_CHAT_COMPLETIONS");
        setup.put("endpoint","https://fixture.example.invalid/v1/"); setup.put("compatibilityProfile","openai-chat-v1");
        setup.put("apiKey","FIXTURE_SECRET_NEVER_PRINT"); setup.put("modelId","fixture-model"); setup.put("capabilityProfile","text-tools-v1");
        setup.put("defaultOptions",Map.of("thinkingMode","DISABLED","maxOutputTokens",2048));
        setup.put("maxContextTokens",32768); setup.put("memoryThreshold",4000); setup.put("enabled",true); setup.put("chatVisible",true); setup.put("makeDefault",true);
        var versions=new LinkedHashMap<String,Integer>(); for(var purpose:ModelPurpose.values())versions.put(purpose.name(),0); setup.put("bindingVersions",versions);
        var saved=call("POST","/api/ai/model-setups",setup,true); assertThat(saved.statusCode()).isEqualTo(200);
        modelId=JSON.readTree(saved.body()).path("data").path("model").path("id").asText();
        Path driver=Files.write(root.resolve("fixture-driver.jar"),new byte[0]);
        var connection=call("POST","/api/connections/create",Map.of("name","Fixture db","dbType","mysql","host","example.invalid","port",3306,"username","fixture","driverJarPath",driver.toString()),true);
        assertThat(connection.statusCode()).isEqualTo(200); connectionId=JSON.readTree(connection.body()).path("data").path("id").asLong();
    }
    @AfterAll static void close() { if(context!=null)context.close(); }
    static HttpRequest request(String method,String path,Object body,boolean instance) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
        if(instance)builder.header("X-Local-Runtime-Id",runtime);
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }
    static HttpResponse<String> call(String method,String path,Object body,boolean instance) throws Exception { return HTTP.send(request(method,path,body,instance),HttpResponse.BodyHandlers.ofString()); }
    static long session(boolean database) throws Exception {
        var created=call("POST","/api/local/conversations",database?Map.of("connectionId",connectionId,"catalog","fixture_db"):Map.of(),true);
        assertThat(created.statusCode()).isEqualTo(200);return JSON.readTree(created.body()).path("data").path("conversationId").asLong();
    }
    static HttpResponse<String> turn(long id,String text) throws Exception { return call("POST","/api/local/chat/stream",Map.of("conversationId",id,"message",text,"modelConfigId",modelId),true); }

    static long otherConnection() throws Exception {
        var created=call("POST","/api/connections/create",Map.of("name","Other "+UUID.randomUUID(),"dbType","dm","host","example.invalid","port",5236,
                "username","fixture","driverJarPath",root.resolve("fixture-driver.jar").toString()),true);
        assertThat(created.statusCode()).isEqualTo(200);return JSON.readTree(created.body()).path("data").path("id").asLong();
    }
    @Test void localChatModelEndpointExposesAndRunsAllFourQwenChoicesWithoutChangingDefaults() throws Exception {
        var setup = new LinkedHashMap<String,Object>();
        setup.put("name", "Qwen chat choices"); setup.put("provider", "QWEN");
        setup.put("apiStyle", "OPENAI_CHAT_COMPLETIONS"); setup.put("compatibilityProfile", "qwen-chat-v1");
        setup.put("endpoint", "https://fixture.example.invalid/v1/"); setup.put("apiKey", "QWEN_FIXTURE_SECRET_NEVER_PRINT");
        setup.put("modelId", "qwen3.8-max"); setup.put("makeDefault", false);
        var saved = call("POST", "/api/ai/model-setups", setup, true);
        assertThat(saved.statusCode()).isEqualTo(200);
        var bindings = call("GET", "/api/ai/model-bindings", null, true).body();
        Path modelFile = root.resolve("models/PERSONAL/1/models.json");
        String before = Files.readString(modelFile);

        // Use the real local composition and browser-origin transport, not a standalone controller.
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/ai/models"))
                .header("Origin", "http://127.0.0.1:5174").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var all = JSON.readTree(response.body()).path("data");
        var qwen = new ArrayList<JsonNode>();
        all.forEach(option -> { if (option.path("provider").asText().equals("QWEN")) qwen.add(option); });
        assertThat(qwen).extracting(option -> option.path("modelId").asText())
                .containsExactly("qwen3.8-max", "qwen3.6-flash", "qwen-plus", "qwen3.5-plus");
        assertThat(response.body()).doesNotContain("QWEN_FIXTURE_SECRET_NEVER_PRINT", "credentialRef", "encryptedPayload", token);
        var defaults = new ArrayList<String>();
        all.forEach(option -> { if (option.path("defaultModel").asBoolean()) defaults.add(option.path("modelConfigId").asText()); });
        assertThat(defaults).containsExactly(modelId);
        long id = session(false);
        try {
            for (var option : qwen) {
                String selected = option.path("modelConfigId").asText();
                assertThat(option.path("modelName").asText()).isEqualTo(selected);
                assertThat(option.path("apiStyle").asText()).isEqualTo("OPENAI_CHAT_COMPLETIONS");
                assertThat(option.path("compatibilityProfile").asText()).isEqualTo("qwen-chat-v1");
                var turn = call("POST", "/api/local/chat/stream",
                        Map.of("conversationId", id, "message", "hello", "modelConfigId", selected), true);
                assertThat(turn.statusCode()).isEqualTo(200);
                assertThat(turn.body()).contains("SUCCEEDED").doesNotContain("errorCode");
                assertThat(context.getBean(LocalConversationRegistry.class).require(id).modelConfigId).isEqualTo(selected);
            }
        } finally { call("DELETE", "/api/local/conversations/" + id, null, true); }
        assertThat(call("GET", "/api/ai/model-bindings", null, true).body()).isEqualTo(bindings);
        assertThat(Files.readString(modelFile)).isEqualTo(before);
    }

    @Test void completeReferencesReachTheLocalModelAndExplicitToolTargetsWithoutChangingDefaults() throws Exception {
        long id=session(true),other=otherConnection();clearInvocations(TABLES);
        var refs=List.of(Map.of("token","@orders","connectionId",connectionId,"connectionName","spoof","catalogName","fixture_db","objectType","TABLE","objectName","orders"),
                Map.of("token","@other/ARCHIVE/orders","connectionId",other,"connectionName","spoof","schemaName","ARCHIVE","objectType","TABLE","objectName","orders"));
        var response=call("POST","/api/local/chat/stream",Map.of("conversationId",id,"message","reference-tools @orders @other/ARCHIVE/orders",
                "modelConfigId",modelId,"userMentions",refs),true);
        // The deterministic model decodes the actual UserMessage before returning the real tool calls.
        assertThat(response.body()).contains("SUCCEEDED","TOOL_RESULT");
        var user=lastRequest.get().messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).filter(m -> m.singleText().contains("reference-tools")).findFirst().orElseThrow();
        var turn=edu.zsc.ai.domain.service.agent.ChatTurnContext.decode(user.singleText());
        assertThat(turn.userMentions()).hasSize(2);assertThat(turn.defaultTarget().getConnectionId()).isEqualTo(connectionId);
        assertThat(turn.userMentions()).extracting(edu.zsc.ai.api.model.request.ChatUserMention::getConnectionName).doesNotContain("spoof");
        verify(TABLES).getTables(new edu.zsc.ai.domain.model.context.DbContext(connectionId,"fixture_db",null));
        verify(TABLES).getTables(new edu.zsc.ai.domain.model.context.DbContext(other,null,"ARCHIVE"));
        assertThat(call("GET","/api/local/conversations/"+id,null,true).body()).contains("fixture_db").doesNotContain("ARCHIVE");
    }
    @Test void localDefaultUpdatesAreIsolatedAndPendingApprovalKeepsItsOriginalTarget() throws Exception {
        long id=session(true),otherSession=session(true);turn(id,"propose");String proposal=proposalId(id);
        var updated=call("PUT","/api/local/conversations/"+id+"/default-target",Map.of("connectionId",connectionId,"catalogName","changed_db"),true);
        assertThat(updated.statusCode()).isEqualTo(200);assertThat(updated.body()).contains("changed_db");
        assertThat(call("GET","/api/local/conversations/"+otherSession,null,true).body()).contains("fixture_db").doesNotContain("changed_db");
        assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(id,proposal,"APPROVE"),true).body()).contains("COMMITTED");
        verify(WRITES).execute(argThat(operation -> operation.proposal.conversationId()==id && operation.proposal.catalogName().equals("fixture_db")));
        assertThat(call("PUT","/api/local/conversations/"+id+"/default-target",Map.of(),false).statusCode()).isEqualTo(409);
    }
    @Test void localCreateAcceptsLegacySchemaNamesAndDoesNotInventACatalog() throws Exception {
        long other=otherConnection();
        var created=call("POST","/api/local/conversations",Map.of("connectionId",other,"catalog","pseudo","schema","ARCHIVE"),true);
        long id=JSON.readTree(created.body()).path("data").path("conversationId").asLong();
        var target=JSON.readTree(call("GET","/api/local/conversations/"+id,null,true).body()).path("data").path("defaultTarget");
        assertThat(target.path("schemaName").asText()).isEqualTo("ARCHIVE");assertThat(target.path("catalogName").isNull()).isTrue();
    }
    @Test void piEntryIsDisabledByDefault() throws Exception {
        long id=session(true);
        var response=call("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"message","hi","modelConfigId",modelId),true);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("LOCAL_FEATURE_NOT_SUPPORTED");
        var capabilities=call("GET","/api/runtime/capabilities",null,true);
        assertThat(JSON.readTree(capabilities.body()).path("data").path("features").path("piAssistant").path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(JSON.readTree(capabilities.body()).path("data").path("features").path("piAssistant").path("reasonCode").asText()).isEqualTo("PI_DISABLED");
    }
    @Test void configuredAssistantIsReadyWithoutAccountOrApplicationDataSource() throws Exception {
        var response=call("GET","/api/runtime/capabilities",null,true); assertThat(response.body()).contains("READY").doesNotContain(token,"FIXTURE_SECRET_NEVER_PRINT",root.toString());
        assertThat(JSON.readTree(response.body()).path("data").path("features").path("aiAssistant").path("state").asText()).isEqualTo("READY");
        assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(edu.zsc.ai.domain.service.ai.AiConversationService.class)).isEmpty();
    }
    @Test void historyStaysInMemoryAndConversationsAreIsolated(CapturedOutput output) throws Exception {
        long first=session(false),second=session(false);
        assertThat(turn(first,"MEMORY_SENTINEL").body()).contains("SUCCEEDED","MEMORY_SENTINEL");
        assertThat(turn(first,"follow-up").body()).contains("remembered MEMORY_SENTINEL");
        assertThat(turn(second,"follow-up").body()).doesNotContain("MEMORY_SENTINEL");
        try(var paths=Files.walk(root)) { for(Path path:paths.filter(Files::isRegularFile).toList()) assertThat(new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("MEMORY_SENTINEL"); }
        assertThat(output.getAll()).contains("MEMORY_SENTINEL").doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
    }
    @Test void rapidModelChunksAreBufferedUntilTheSseConsumerReadsThem() throws Exception {
        var response=turn(session(false),"burst");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("SUCCEEDED").doesNotContain("LOCAL_STREAM_FAILED","MODEL_PROTOCOL_ERROR");
        assertThat(response.body().split("\"type\":\"TEXT\"",-1)).hasSize(2001);
    }
    @Test void sdkExecutesOnlyFixedReadToolsAndRejectsWritesBeforeDatabaseExecution() throws Exception {
        long id=session(true); clearInvocations(SQL,TABLES);
        assertThat(turn(id,"tool").body()).contains("safe_table","TOOL_RESULT","SUCCEEDED");
        verify(TABLES).getTables(new edu.zsc.ai.domain.model.context.DbContext(connectionId,"fixture_db",null));
        assertThat(turn(id,"write").body()).contains("LOCAL_FEATURE_NOT_SUPPORTED"); verifyNoInteractions(SQL);
        assertThat(turn(id,"query").body()).contains("ROW_COUNT"); verify(SQL).executeSql(argThat(r -> r.getConnectionId()==connectionId && "fixture_db".equals(r.getCatalog())));
    }
    @Test void unscopedChatCanDiscoverSourcesAndNamespacesThenReadAnExplicitTarget() throws Exception {
        long id=session(false);clearInvocations(TABLES,SQL,DATABASES,SCHEMAS);
        try {
            var sources=turn(id,"sources");
            assertThat(sources.body()).contains("list_connections","Fixture db","SUCCEEDED")
                    .doesNotContain("example.invalid","fixture-driver.jar","FIXTURE_SECRET_NEVER_PRINT","credentialConfigured");
            verifyNoInteractions(TABLES,SQL,DATABASES,SCHEMAS);
            String target=JSON.writeValueAsString(Map.of("connectionId",connectionId));
            assertThat(turn(id,"list_namespaces:"+target).body()).contains("fixture_db","SUCCEEDED");
            verify(DATABASES).getDatabases(connectionId);
            assertThat(turn(id,"list_tables:"+JSON.writeValueAsString(Map.of("connectionId",connectionId,"catalogName","fixture_db"))).body())
                    .contains("safe_table","SUCCEEDED");
            verify(TABLES).getTables(new edu.zsc.ai.domain.model.context.DbContext(connectionId,"fixture_db",null));
            assertThat(JSON.readTree(call("GET","/api/local/conversations/"+id,null,true).body()).path("data").path("defaultTarget").path("connectionId").isNull()).isTrue();
        } finally {call("DELETE","/api/local/conversations/"+id,null,true);}
    }
    @Test void unknownToolsCannotReachAnExecutor() throws Exception {
        clearInvocations(SQL,TABLES); assertThat(turn(session(true),"unknown").body()).contains("TOOL_FORBIDDEN","FAILED"); verifyNoInteractions(SQL,TABLES);
    }
    @Test void missingRuntimeAndClosedConversationFailExplicitly() throws Exception {
        assertThat(call("POST","/api/local/conversations",Map.of(),false).statusCode()).isEqualTo(409);
        long id=session(false); assertThat(call("DELETE","/api/local/conversations/"+id,null,true).statusCode()).isEqualTo(200);
        assertThat(turn(id,"old").statusCode()).isEqualTo(410);
    }
    @Test void damagedModelKeyDoesNotPretendAssistantIsReadyOrDisableTheWorkbench() throws Exception {
        Path key=root.resolve("models/.keys/V1.key"); byte[] original=Files.readAllBytes(key);
        try {
            Files.writeString(key,"damaged-key");
            var data=JSON.readTree(call("GET","/api/runtime/capabilities",null,true).body()).path("data").path("features");
            assertThat(data.path("aiAssistant").path("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(data.path("databaseWorkbench").path("state").asText()).isEqualTo("READY");
            assertThat(Files.readString(key)).isEqualTo("damaged-key");
        } finally { Files.write(key,original); }
    }
    @Test void oneRunPerSessionAndCancellationReleasesTheSession() throws Exception {
        long id=session(false); waiting.set(new CountDownLatch(1));
        var running=HTTP.sendAsync(request("POST","/api/local/chat/stream",Map.of("conversationId",id,"message","wait","modelConfigId",modelId),true),HttpResponse.BodyHandlers.ofString());
        assertThat(waiting.get().await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(turn(id,"concurrent").statusCode()).isEqualTo(409);
        assertThat(call("POST","/api/local/conversations/"+id+"/cancel",null,true).body()).contains("CANCEL_REQUESTED");
        assertThat(running.get(5,TimeUnit.SECONDS).body()).contains("CANCELLED");
        assertThat(turn(id,"after cancel").body()).contains("SUCCEEDED");
    }
    static String proposalId(long id) throws Exception {
        var response=call("GET","/api/local/conversations/"+id+"/write-proposals",null,true);
        return JSON.readTree(response.body()).path("data").get(0).path("proposal").path("proposalId").asText();
    }
    static Map<String,Object> approveBody(long id,String proposal,String decision) {return Map.of("conversationId",id,"proposalId",proposal,"decision",decision);}
    @Test void proposedWriteRequiresExactSingleUseApprovalAndFeedsOutcomeBackToNextTurn(CapturedOutput output) throws Exception {
        long id=session(true);int before=writeCalls.get();
        assertThat(turn(id,"propose").body()).contains("WAITING_CONFIRMATION","statistics");
        assertThat(writeCalls).hasValue(before);String proposal=proposalId(id);
        assertThat(turn(id,"skip approval").statusCode()).isEqualTo(409);
        var tampered=new LinkedHashMap<>(approveBody(id,proposal,"APPROVE"));tampered.put("sql","DELETE FROM other_table");
        assertThat(call("POST","/api/permissions/write-executions/approve",tampered,true).statusCode()).isEqualTo(400);
        tampered.remove("sql"); tampered.put("grantPreset","ALWAYS_ALLOW");
        assertThat(call("POST","/api/permissions/write-executions/approve",tampered,true).statusCode()).isEqualTo(409);
        assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(session(true),proposal,"APPROVE"),true).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(id,proposal,"APPROVE"),false).statusCode()).isEqualTo(409);
        assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(id,proposal,"APPROVE"),true).body()).contains("COMMITTED","SUCCEEDED");
        assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(id,proposal,"APPROVE"),true).statusCode()).isEqualTo(409);
        assertThat(writeCalls).hasValue(before+1);
        turn(id,"follow up write result");assertThat(lastRequest.get().messages().toString()).contains("COMMITTED");
        assertThat(output.getAll()).contains("propose  literal");
        try(var paths=Files.walk(root)) { for(Path path:paths.filter(Files::isRegularFile).toList()) assertThat(new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("propose  literal"); }
    }
    @Test void unknownResultCannotBeApprovedAgainAndRejectCancelAndConnectionCloseInvalidatePending() throws Exception {
        long id=session(true);turn(id,"propose-unknown");String p=proposalId(id);
        assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(id,p,"APPROVE"),true).body()).contains("UNKNOWN");
        assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(id,p,"APPROVE"),true).statusCode()).isEqualTo(409);
        for(String action:List.of("reject","cancel","close")) {
            long current=session(true);turn(current,"propose");String proposal=proposalId(current);
            if(action.equals("reject")) call("POST","/api/permissions/write-executions/approve",approveBody(current,proposal,"REJECT"),true);
            else if(action.equals("cancel")) call("POST","/api/local/conversations/"+current+"/cancel",null,true);
            else call("DELETE","/api/connections/active/"+connectionId,null,true);
            assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(current,proposal,"APPROVE"),true).statusCode()).isEqualTo(409);
        }
    }
    @Test void concurrentApprovalAndCancellationDoNotReplayAnInflightOperation() throws Exception {
        long id=session(true);turn(id,"propose-wait");String p=proposalId(id);int before=writeCalls.get();
        writeStarted.set(new CountDownLatch(1));writeRelease.set(new CountDownLatch(1));
        var attempt=HTTP.sendAsync(request("POST","/api/permissions/write-executions/approve",approveBody(id,p,"APPROVE"),true),HttpResponse.BodyHandlers.ofString());
        assertThat(writeStarted.get().await(5,TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(call("POST","/api/permissions/write-executions/approve",approveBody(id,p,"APPROVE"),true).statusCode()).isEqualTo(409);
            assertThat(turn(id,"parallel turn").statusCode()).isEqualTo(409);
            call("POST","/api/local/conversations/"+id+"/cancel",null,true);
        } finally { writeRelease.get().countDown(); }
        assertThat(attempt.get(5,TimeUnit.SECONDS).body()).contains("CANCELLED");assertThat(writeCalls).hasValue(before+1);
    }
    @Test void explicitCompactionRetainsPairedRecentToolHistory() throws Exception {
        long id=session(true); turn(id,"first question"); turn(id,"tool");
        assertThat(call("POST","/api/local/conversations/"+id+"/compact",null,true).body()).contains("\"compacted\":true");
        turn(id,"continue");
        assertThat(lastRequest.get().messages().toString()).contains("summary: retained","safe_table");
    }
}
