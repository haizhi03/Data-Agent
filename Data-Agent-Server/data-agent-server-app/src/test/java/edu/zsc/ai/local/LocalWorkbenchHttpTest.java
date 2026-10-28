package edu.zsc.ai.local;

import edu.zsc.ai.DataAgentApplication;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Empty isolated repository: never uses the user's real configuration or business databases. */
class LocalWorkbenchHttpTest {
    @TempDir static Path directory;
    static ServletWebServerApplicationContext context;
    @BeforeAll static void start() {
        context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(DataAgentApplication.class).run(
                "--app.runtime.mode=local", "--server.port=0", "--spring.main.banner-mode=off",
                "--app.local.data-directory=" + directory, "--logging.config=classpath:local-runtime-logback.xml");
    }
    @AfterAll static void close() { if (context != null) context.close(); }
    static HttpResponse<String> call(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path))
                .header("Authorization", "Bearer " + context.getBean(LocalRuntimeIdentity.class).transportTokenForHost())
                .header("Content-Type", "application/json").method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest @ValueSource(strings = {"DELETE /api/databases", "DELETE /api/tables", "PUT /api/tables/rename",
            "POST /api/tables/rows", "PUT /api/tables/rows", "DELETE /api/tables/rows", "POST /api/tables/rows/batch",
            "POST /api/tables/import", "DELETE /api/views", "DELETE /api/functions", "DELETE /api/procedures",
            "DELETE /api/triggers", "DELETE /api/sequences", "DELETE /api/constraints"})
    void legacyWritesAreRejectedBeforeValidationOrServiceInvocation(String target) throws Exception {
        String[] parts = target.split(" "); var response = call(parts[0], parts[1], "{}");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("LOCAL_FEATURE_NOT_SUPPORTED");
        assertThat(directory.resolve("connections.json")).doesNotExist();
    }
    @Test void mixedScriptAndInjectedPreviewPredicateAreRejectedWithoutOpeningAConnection() throws Exception {
        assertThat(call("POST", "/api/db/sql/execute-script", "{\"connectionId\":1,\"sql\":\"SELECT 1; DELETE FROM t\"}").statusCode()).isEqualTo(409);
        assertThat(call("GET", "/api/tables/data?connectionId=1&tableName=t&whereClause=1%3D1%3BDELETE%20FROM%20t", null).statusCode()).isEqualTo(409);
        assertThat(call("GET", "/api/tables/data?connectionId=1&tableName=t&pageSize=0", null).statusCode()).isEqualTo(400);
        assertThat(directory.resolve("connections.json")).doesNotExist();
    }
    @Test void corruptedStoreIsReportedAsUnavailableWithoutPretendingWorkbenchIsReady() throws Exception {
        Path path = directory.resolve("connections.json"); Files.writeString(path, "{broken}");
        try {
            var response = call("GET", "/api/runtime/capabilities", null);
            assertThat(response.statusCode()).isEqualTo(200);
            var feature = LocalConnectionRepository.JSON.readTree(response.body()).path("data").path("features").path("databaseWorkbench");
            assertThat(feature.path("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(feature.path("reasonCode").asText()).isEqualTo("LOCAL_STORE_UNAVAILABLE");
            assertThat(Files.readString(path)).isEqualTo("{broken}");
        } finally { Files.delete(path); }
    }
    @Test void driverManagementCannotEscapeItsLocalRoot() throws Exception {
        assertThat(call("GET", "/api/drivers/installed?databaseType=..%2F..", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/drivers/installed?databaseType=mysql", null).statusCode()).isEqualTo(200);
        assertThat(directory.resolve("drivers/mysql")).isDirectory();
    }
}
