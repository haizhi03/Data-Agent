package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import edu.zsc.ai.DataAgentApplication;
import edu.zsc.ai.config.ai.model.ModelPurpose;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import edu.zsc.ai.domain.service.db.TableService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real local HTTP + production Pi workers + TLS model fixture + Java metadata boundary. No vendor or DB calls. */
class LocalPiExplorerWorkerHttpTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> fixtureFailure=new AtomicReference<>();
    ServletWebServerApplicationContext app;
    HttpsServer provider;
    String base,token,runtime;
    long connectionId;

    @Configuration(proxyBeanMethods=false) static class Fixture {
        @Bean @Primary TableService fixtureTables(){
            var tables=mock(TableService.class);when(tables.getTables(any())).thenReturn(List.of("safe_table"));return tables;
        }
    }

    @AfterEach void close(){if(app!=null)app.close();if(provider!=null)provider.stop(0);}

    @Test @Timeout(45) void mainDelegatesToTheConfiguredExplorerAndContinuesWithItsVerifiedResult() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),"Node wrapper fixture uses POSIX executable permissions");
        var node=new ProcessBuilder("node","-p","process.execPath").start();
        String nodeBinary=new String(node.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        assertThat(node.waitFor()).isZero();
        Path keys=root.resolve("fixture.p12"),certificate=root.resolve("fixture.pem");
        String keytool=Path.of(System.getProperty("java.home"),"bin","keytool").toString();
        run(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=127.0.0.1",
                "-ext","SAN=ip:127.0.0.1","-keystore",keys.toString(),"-storetype","PKCS12","-storepass","fixture-password","-noprompt");
        run(keytool,"-exportcert","-rfc","-alias","fixture","-keystore",keys.toString(),"-storepass","fixture-password","-file",certificate.toString());
        KeyStore store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keys)){store.load(input,"fixture-password".toCharArray());}
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(store,"fixture-password".toCharArray());
        var tls=SSLContext.getInstance("TLS");tls.init(managers.getKeyManagers(),null,null);
        provider=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);provider.setHttpsConfigurator(new HttpsConfigurator(tls));
        provider.createContext("/v1/chat/completions",this::respond);provider.start();
        Path wrapper=root.resolve("fixture-node");
        Files.writeString(wrapper,"#!/bin/sh\nexport NODE_EXTRA_CA_CERTS="+quote(certificate.toString())+"\nexec "+quote(nodeBinary)+" \"$@\"\n");
        Files.setPosixFilePermissions(wrapper,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path worker=new LocalPiProperties(true,null,null,0,0,0).resolveWorkerScript();assertThat(worker).isRegularFile();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(DataAgentApplication.class,Fixture.class).run(
                "--server.port=0","--spring.main.banner-mode=off","--app.local.data-directory="+root,
                "--app.local.pi.enabled=true","--app.local.pi.node-binary="+wrapper,
                "--app.local.model-allowed-private-hosts=127.0.0.1");
        var identity=app.getBean(LocalRuntimeIdentity.class);token=identity.transportTokenForHost();runtime=identity.runtimeInstanceId().toString();
        base="http://127.0.0.1:"+app.getWebServer().getPort();
        String modelEndpoint="https://127.0.0.1:"+provider.getAddress().getPort()+"/v1/";
        var main=setup("main-fixture","fixture-main-key",modelEndpoint,true);
        var explorer=setup("explorer-fixture","fixture-explorer-key",modelEndpoint,false);
        call("PUT","/api/ai/model-bindings/EXPLORER",Map.of("expectedVersion",1,"modelConfigId",explorer.path("model").path("id").asText()));
        Path driver=Files.write(root.resolve("fixture-driver.jar"),new byte[0]);
        connectionId=call("POST","/api/connections/create",Map.of("name","Fixture database","dbType","mysql","host","example.invalid","port",3306,
                "username","fixture","driverJarPath",driver.toString())).path("id").asLong();
        long id=call("POST","/api/local/conversations",Map.of("connectionId",connectionId,"catalogName","fixture_db")).path("conversationId").asLong();
        var response=send("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"message","Explore the available tables","modelConfigId",main.path("model").path("id").asText()));
        assertThat(fixtureFailure.get()).isNull();assertThat(response.statusCode()).isEqualTo(200);
        String body=response.body();
        assertThat(body).contains("SUB_AGENT_START","SUB_AGENT_COMPLETE","Explorer verified safe_table","SUCCEEDED")
                .doesNotContain("fixture-main-key","fixture-explorer-key","PI_EXPLORER_RESULT_INVALID");
        var events=new ArrayList<JsonNode>();for(String line:body.split("\n"))if(line.startsWith("data:"))events.add(json.readTree(line.substring(5)));
        assertThat(events.stream().filter(event->event.path("done").asBoolean())).hasSize(1);
        assertThat(json.readTree(events.getLast().path("data").asText()).path("statistics").path("modelCalls").asInt()).isEqualTo(4);
        assertThat(requests).extracting(request->request.path("model").asText()).containsExactly("main-fixture","explorer-fixture","explorer-fixture","main-fixture");
        for(var request:requests){
            var names=new ArrayList<String>();request.path("tools").forEach(tool->names.add(tool.path("function").path("name").asText()));
            if(request.path("model").asText().equals("explorer-fixture"))assertThat(names).doesNotContain("callingExplorerSubAgent");
            else assertThat(names).contains("callingExplorerSubAgent");
        }
    }

    private JsonNode setup(String model,String key,String endpoint,boolean main) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("name",model);body.put("modelId",model);body.put("apiKey",key);
        body.put("provider","CUSTOM");body.put("apiStyle","OPENAI_CHAT_COMPLETIONS");body.put("compatibilityProfile","openai-chat-v1");
        body.put("endpoint",endpoint);body.put("makeDefault",main);
        if(main){var versions=new LinkedHashMap<String,Integer>();for(var purpose:ModelPurpose.values())versions.put(purpose.name(),0);body.put("bindingVersions",versions);}
        return call("POST","/api/ai/model-setups",body);
    }
    private JsonNode call(String method,String path,Object body) throws Exception {var response=send(method,path,body);assertThat(response.statusCode()).as(path+" "+response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");}
    private HttpResponse<String> send(String method,String path,Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+token).header("X-Local-Runtime-Id",runtime).header("Content-Type","application/json")
                .method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void respond(HttpExchange exchange) {
        try {
            var body=json.readTree(exchange.getRequestBody());requests.add(body);
            boolean explorer=body.path("model").asText().equals("explorer-fixture");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+(explorer?"fixture-explorer-key":"fixture-main-key"));
            var tools=new ArrayList<JsonNode>();body.path("messages").forEach(message->{if(message.path("role").asText().equals("tool"))tools.add(message);});
            Map<String,Object> delta;String reason;
            if(tools.isEmpty()) {
                String name=explorer?"list_tables":"callingExplorerSubAgent";
                Object args=explorer?Map.of():Map.of("tasks",List.of(Map.of("connectionId",connectionId,"instruction","Find relevant tables")));
                delta=Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id",explorer?"child-read":"delegate","type","function",
                        "function",Map.of("name",name,"arguments",json.writeValueAsString(args)))));reason="tool_calls";
            } else {
                String content;
                if(explorer){assertThat(tools.getLast().path("content").asText()).contains("safe_table");content=json.writeValueAsString(Map.of("summaryText","Verified safe_table","objects",List.of(Map.of("objectName","safe_table","objectType","TABLE","relevanceScore",90))));}
                else {assertThat(tools.getLast().path("content").asText()).contains("SUCCESS","safe_table");content="Explorer verified safe_table";}
                delta=Map.of("role","assistant","content",content);reason="stop";
            }
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            var output=exchange.getResponseBody();
            for(var choice:List.of(Map.of("index",0,"delta",delta,"finish_reason",""),Map.of("index",0,"delta",Map.of(),"finish_reason",reason))) {
                var chunk=Map.of("id","fixture","object","chat.completion.chunk","created",0,"model",body.path("model").asText(),"choices",List.of(choice));
                output.write(("data: "+json.writeValueAsString(chunk)+"\n\n").getBytes(StandardCharsets.UTF_8));
            }
            output.write(("data: "+json.writeValueAsString(Map.of("choices",List.of(),"usage",Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18)))+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));output.close();
        } catch(Throwable failure){fixtureFailure.set(failure);exchange.close();}
    }
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private static void run(String... command) throws Exception {var process=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(process.waitFor()).as(output).isZero();}
}
