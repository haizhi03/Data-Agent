package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.*;
import edu.zsc.ai.DataAgentApplication;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.service.db.TableService;
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
 * Protocol v2 (durable worker) verification: real HTTP/SSE plus real Node processes running
 * pi-fixture-worker-v2.mjs, whose durable store is a JSON file in the test temp directory.
 * No model vendor and no business database calls. v1 behavior stays covered by LocalPiChatHttpTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class LocalPiProtocolV2HttpTest {
    @TempDir static Path root;
    static ServletWebServerApplicationContext context;
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static String base, token, runtime, modelId;
    static long connectionId;
    static Path stateFile;
    static final TableService TABLES=mock(TableService.class);

    @Configuration(proxyBeanMethods=false) static class Fixture {
        @Bean @Primary TableService fixtureTables() {
            when(TABLES.getTables(any())).thenReturn(List.of("safe_table")); return TABLES;
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
        Path fixture=Path.of("src/test/resources/pi-fixture-worker-v2.mjs").toAbsolutePath();
        assertThat(fixture).isRegularFile();
        stateFile=root.resolve("pi-v2-state.json");
        // The wrapper pins the fixture's durable store to this test run's temp directory.
        Path wrapper=root.resolve("pi-fixture-worker-v2-wrapper.mjs");
        Files.writeString(wrapper,"process.env.PI_FIXTURE_V2_STATE = "+JSON.writeValueAsString(stateFile.toString())+";\n"
                +"await import("+JSON.writeValueAsString(fixture.toUri().toURL().toString())+");\n");
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(DataAgentApplication.class,Fixture.class).run(
                "--app.runtime.mode=local","--server.port=0","--spring.main.banner-mode=off","--app.local.data-directory="+root,
                "--app.local.pi.enabled=true","--app.local.pi.worker-script="+wrapper,
                "--app.local.pi.startup-timeout-ms=20000","--app.local.pi.cancel-grace-seconds=3");
        base="http://127.0.0.1:"+context.getWebServer().getPort(); var identity=context.getBean(LocalRuntimeIdentity.class);
        token=identity.transportTokenForHost(); runtime=identity.runtimeInstanceId().toString();
        var setup=new LinkedHashMap<String,Object>();
        setup.put("name","Fixture v2"); setup.put("provider","CUSTOM"); setup.put("apiStyle","OPENAI_CHAT_COMPLETIONS");
        setup.put("endpoint","https://fixture.example.invalid/v1/"); setup.put("compatibilityProfile","openai-chat-v1");
        setup.put("apiKey","FIXTURE_SECRET_NEVER_PRINT"); setup.put("modelId","fixture-model"); setup.put("capabilityProfile","text-tools-v1");
        setup.put("defaultOptions",Map.of("thinkingMode","DISABLED","maxOutputTokens",2048));
        setup.put("maxContextTokens",32768); setup.put("memoryThreshold",4000); setup.put("enabled",true); setup.put("chatVisible",true); setup.put("makeDefault",true);
        var versions=new LinkedHashMap<String,Integer>(); for(var purpose:ModelPurpose.values())versions.put(purpose.name(),0); setup.put("bindingVersions",versions);
        var saved=call("POST","/api/ai/model-setups",setup,true);
        modelId=JSON.readTree(saved.body()).path("data").path("model").path("id").asText();
        Path driver=Files.write(root.resolve("fixture-driver.jar"),new byte[0]);
        var connection=call("POST","/api/connections/create",Map.of("name","Fixture db v2","dbType","mysql","host","example.invalid","port",3306,
                "username","fixture","driverJarPath",driver.toString()),true);
        connectionId=JSON.readTree(connection.body()).path("data").path("id").asLong();
    }
    @AfterAll static void close() { if(context!=null)context.close(); }

    static HttpRequest request(String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(120))
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
    static String piTurn(long id,String text) throws Exception {
        return call("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"message",text,"modelConfigId",modelId),true).body();
    }
    static List<JsonNode> blocks(String sse) throws Exception {
        var result=new ArrayList<JsonNode>();
        for(String line:sse.split("\n")) if(line.startsWith("data:")) result.add(JSON.readTree(line.substring(5).trim()));
        return result;
    }
    static String runIdOf(String sse) throws Exception {
        for(var block:blocks(sse)) if(block.path("done").asBoolean())
            return JSON.readTree(block.path("data").asText()).path("runId").asText();
        throw new AssertionError("done block missing");
    }
    static JsonNode state() throws Exception {
        return JSON.readTree(Files.readString(stateFile));
    }

    @Test void negotiatesProtocol2AndStampsIdempotencyKey(CapturedOutput output) throws Exception {
        long id=session(false);
        String body=piTurn(id,"hello v2");
        assertThat(body).contains("pong-v2","SUCCEEDED");
        assertThat(body).doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
        assertThat(blocks(body).stream().filter(block->block.path("done").asBoolean())).hasSize(1);
        assertThat(output).contains("protocol=2");
        var run=state().path("runs").path(runIdOf(body));
        assertThat(run.path("idempotencyKey").asText()).isEqualTo(runIdOf(body));
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test void recoveredRunReattachesAndExecutesTheToolOnlyOnce(CapturedOutput output) throws Exception {
        long id=session(true); clearInvocations(TABLES);
        String body=piTurn(id,"crash-after-result");
        String runId=runIdOf(body);
        assertThat(body).contains("SUCCEEDED","tool-answer:false:");
        assertThat(blocks(body).stream().filter(block->block.path("done").asBoolean())).hasSize(1);
        // The re-sent tool_call was served from the run-scoped cache: one TOOL_CALL/TOOL_RESULT pair,
        // and the underlying metadata read executed exactly once across both worker processes.
        assertThat(blocks(body).stream().filter(block->block.path("type").asText().equals("TOOL_CALL"))).hasSize(1);
        assertThat(blocks(body).stream().filter(block->block.path("type").asText().equals("TOOL_RESULT"))).hasSize(1);
        Thread.sleep(500);
        verify(TABLES,times(1)).getTables(any());
        // Recovery ordering: the re-attaching start (same idempotencyKey) precedes any tool_result.
        var run=state().path("runs").path(runId);
        assertThat(run.path("reattached").asInt()).isEqualTo(1);
        int boots=state().path("boots").asInt();
        String protocolSessionId="conversation-"+id;
        var inbound=new ArrayList<String>();
        for(var entry:state().path("inbound")) {
            boolean sameRun=runId.equals(entry.path("runId").asText(null));
            boolean sameSessionAttach=protocolSessionId.equals(entry.path("sessionId").asText(null));
            if(entry.path("boot").asInt()==boots && (sameRun || sameSessionAttach))
                inbound.add(entry.path("type").asText());
        }
        assertThat(inbound).containsSubsequence("start","attach","tool_result");
        assertThat(output).contains("Pi run reattached after worker restart");
        assertThat(body).doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
        assertThat(output).doesNotContain("FIXTURE_SECRET_NEVER_PRINT");
    }

    @Test void textResetNotifiesTheFrontendToRebuild() throws Exception {
        long id=session(false);
        String body=piTurn(id,"reset-text");
        assertThat(body).contains("SUCCEEDED","fresh");
        var statuses=blocks(body).stream()
                .filter(block->block.path("type").asText().equals("STATUS") && block.path("data").asText().equals("TEXT_RESET"))
                .toList();
        assertThat(statuses).hasSize(1);
    }

    @Test void replayedEventsWithASeenSeqAreDeliveredOnlyOnce() throws Exception {
        long id=session(false);
        String body=piTurn(id,"dupe-events");
        assertThat(body).contains("SUCCEEDED").doesNotContain("PI_WORKER_ERROR");
        var texts=blocks(body).stream()
                .filter(block->block.path("type").asText().equals("TEXT") && block.path("data").asText().equals("once"))
                .toList();
        assertThat(texts).hasSize(1);
        assertThat(blocks(body).stream().filter(block->block.path("done").asBoolean())).hasSize(1);
    }

    @Test void cancelledRunSurvivingInStorageIsNotRevivedAfterRestart() throws Exception {
        long id=session(true); clearInvocations(TABLES);
        var pending=CompletableFuture.supplyAsync(() -> {
            try { return piTurn(id,"cancel-loss"); } catch (Exception e) { throw new RuntimeException(e); }
        });
        verify(TABLES,timeout(10000).times(1)).getTables(any());
        Thread.sleep(300); // let the tool_result reach the fixture before the cancel
        call("POST","/api/local/conversations/"+id+"/cancel",null,true);
        String cancelled=pending.get(20,TimeUnit.SECONDS);
        assertThat(cancelled).contains("CANCELLED").doesNotContain("SUCCEEDED");
        String runId=runIdOf(cancelled);
        // The worker died before it could settle the run: storage still shows RUNNING.
        assertThat(state().path("runs").path(runId).path("status").asText()).isEqualTo("RUNNING");
        // The next turn spawns a fresh worker; the orphan run is cancelled, never re-executed.
        String next=piTurn(id,"hello again afterwards");
        assertThat(next).contains("pong-v2","SUCCEEDED");
        Thread.sleep(500);
        verify(TABLES,times(1)).getTables(any());
        long deadline=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<deadline) {
            if(state().path("runs").path(runId).path("status").asText().equals("CANCELLED")) return;
            Thread.sleep(100);
        }
        assertThat(state().path("runs").path(runId).path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test void workerThatNeverRecoversFailsAfterBoundedRetries() throws Exception {
        long id=session(false);
        String body=piTurn(id,"crash-always");
        assertThat(body).contains("FAILED","PI_WORKER_EXIT");
        assertThat(blocks(body).stream().filter(block->block.path("done").asBoolean())).hasSize(1);
        assertThat(piTurn(id,"hello after repeated crashes")).contains("pong-v2","SUCCEEDED");
    }
}
