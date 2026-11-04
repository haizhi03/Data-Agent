package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.*;
import edu.zsc.ai.DataAgentApplication;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ColumnService;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
    static final ColumnService COLUMNS=mock(ColumnService.class);

    @Configuration(proxyBeanMethods=false) static class Fixture {
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
        Path driver=Files.write(root.resolve("fixture-driver.jar"),new byte[0]);
        var connection=call("POST","/api/connections/create",Map.of("name","Fixture db","dbType","mysql","host","example.invalid","port",3306,"username","fixture","driverJarPath",driver.toString()),true);
        assertThat(connection.statusCode()).isEqualTo(200); connectionId=JSON.readTree(connection.body()).path("data").path("id").asLong();
    }
    @AfterAll static void close() { if(context!=null)context.close(); }

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
        return JSON.readTree(created.body()).path("data").path("conversationId").asLong();
    }
    static String piTurn(long id,String text,List<Map<String,Object>> mentions) throws Exception {
        var body=new LinkedHashMap<String,Object>();
        body.put("conversationId",id); body.put("message",text); body.put("modelConfigId",modelId);
        if (mentions!=null) body.put("userMentions",mentions);
        return call("POST","/api/local/pi/chat/stream",body,true).body();
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
        long id=session(true);
        String body=piTurn(id,"fail-tool now",null);
        assertThat(body).contains("TOOL_RESULT","LOCAL_REQUEST_INVALID","tool-error-seen:true","SUCCEEDED");
        verify(TABLES,never()).getTables(any());
    }

    @Test void multiTurnReusesTheSameWorkerSession() throws Exception {
        long id=session(false);
        assertThat(piTurn(id,"first",null)).contains("pong-turn1","SUCCEEDED");
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
