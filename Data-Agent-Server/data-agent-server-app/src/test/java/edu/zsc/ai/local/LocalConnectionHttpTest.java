package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.JsonNode;
import edu.zsc.ai.DataAgentApplication;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.*;

class LocalConnectionHttpTest {
    @TempDir static Path directory;
    static ServletWebServerApplicationContext context;
    static Path root, driver;
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static ServletWebServerApplicationContext start() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(DataAgentApplication.class).run(
                "--app.runtime.mode=local", "--server.port=0", "--spring.main.banner-mode=off",
                "--app.local.data-directory=" + root, "--logging.config=classpath:local-runtime-logback.xml");
    }
    @BeforeAll static void setup() throws Exception {
        root = directory.resolve("data"); driver = Files.write(directory.resolve("fixture-driver.jar"), new byte[0]); context = start();
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    static HttpResponse<String> call(String method, String path, Object body) throws Exception {
        String token = context.getBean(LocalRuntimeIdentity.class).transportTokenForHost();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(
                LocalConnectionRepository.JSON.writeValueAsBytes(body)));
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    static JsonNode result(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).withFailMessage("Unexpected HTTP response: %s", response.body()).isEqualTo(status);
        return LocalConnectionRepository.JSON.readTree(response.body());
    }
    Map<String, Object> input() {
        var map = new HashMap<String, Object>(); map.put("name", "fixture-" + UUID.randomUUID()); map.put("dbType", "mysql");
        map.put("host", "db.example.invalid"); map.put("port", 3306); map.put("driverJarPath", driver.toString()); return map;
    }

    @Test void crudSurvivesRestartAndUsesExpectedVersionsWithoutReturningSecrets() throws Exception {
        var input = input(); input.put("password", "fixture-http-password"); input.put("properties", Map.of("useSSL", "true", "vendorSecret", "fixture-http-secret"));
        var createdResponse = call("POST", "/api/connections/create", input);
        var created = result(createdResponse, 200).path("data"); long id = created.path("id").asLong();
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertThat(created.path("credentialConfigured").asBoolean()).isTrue();
        assertThat(createdResponse.body()).doesNotContain("credentialRef", "fixture-http-password", "fixture-http-secret");
        input.put("connectionId", id); input.put("expectedVersion", 1); input.put("password", ""); input.put("name", "updated-" + id);
        assertThat(result(call("PUT", "/api/connections", input), 200).path("data").path("version").asLong()).isEqualTo(2);
        assertThat(result(call("PUT", "/api/connections", input), 409).path("reasonCode").asText()).isEqualTo("CONNECTION_REVISION_CONFLICT");
        assertThat(result(call("DELETE", "/api/connections/" + id, null), 400).path("reasonCode").asText()).isEqualTo("LOCAL_REQUEST_INVALID");
        assertThat(result(call("DELETE", "/api/connections/" + id + "?expectedVersion=1", null), 409).path("reasonCode").asText()).isEqualTo("CONNECTION_REVISION_CONFLICT");
        String firstInstance = context.getBean(LocalRuntimeIdentity.class).runtimeInstanceId().toString();
        context.close(); context = start();
        assertThat(context.getBean(LocalRuntimeIdentity.class).runtimeInstanceId().toString()).isNotEqualTo(firstInstance);
        var restored = result(call("GET", "/api/connections/" + id, null), 200).path("data");
        assertThat(restored.path("name").asText()).isEqualTo("updated-" + id);
        assertThat(restored.path("credentialConfigured").asBoolean()).isTrue();
        result(call("DELETE", "/api/connections/" + id + "?expectedVersion=2", null), 200);
        assertThat(result(call("GET", "/api/connections/" + id, null), 404).path("reasonCode").asText()).isEqualTo("CONNECTION_NOT_FOUND");
    }

    @Test void malformedInputsAndForeignScopeUseStableErrors() throws Exception {
        var invalid = input(); invalid.put("port", 65536);
        assertThat(result(call("POST", "/api/connections/create", invalid), 400).path("reasonCode").asText()).isEqualTo("LOCAL_REQUEST_INVALID");
        var foreign = input(); foreign.put("clientOrgId", 42);
        assertThat(result(call("POST", "/api/connections/create", foreign), 403).path("reasonCode").asText()).isEqualTo("LOCAL_SCOPE_FORBIDDEN");
        assertThat(result(call("POST", "/api/connections/open?connectionId=999999", null), 404).path("reasonCode").asText()).isEqualTo("CONNECTION_NOT_FOUND");
    }

    @Test void corruptFileReturns503AndRemainsUntouched() throws Exception {
        result(call("GET", "/api/connections", null), 200);
        Path path = root.resolve("connections.json"); byte[] before = Files.exists(path) ? Files.readAllBytes(path) : null;
        try {
            Files.writeString(path, "{broken}");
            assertThat(result(call("GET", "/api/connections", null), 503).path("reasonCode").asText()).isEqualTo("LOCAL_STORE_UNAVAILABLE");
            assertThat(Files.readString(path)).isEqualTo("{broken}");
        } finally { if (before == null) Files.deleteIfExists(path); else Files.write(path, before); }
    }
}
