package edu.zsc.ai.local;

import edu.zsc.ai.DataAgentApplication;
import edu.zsc.ai.context.RequestContext;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in. Reads existing encrypted local connections and a credential-free fixture manifest; no application database import. */
@EnabledIfEnvironmentVariable(named = "DATA_AGENT_WORKBENCH_LIVE", matches = "true")
class LocalWorkbenchLiveTest {
    static ServletWebServerApplicationContext context;
    static Path directory;
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<Map<String, Object>> summary = new ArrayList<>();
    @BeforeAll static void start() throws Exception {
        directory = Path.of(System.getenv("DATA_AGENT_WORKBENCH_TEST_DIRECTORY")).toAbsolutePath();
        context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(DataAgentApplication.class).run(
                "--app.runtime.mode=local", "--server.port=0", "--spring.main.banner-mode=off",
                "--app.local.data-directory=" + directory, "--logging.config=classpath:local-runtime-logback.xml");
        assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(edu.zsc.ai.domain.service.sys.SysSessionsService.class)).isEmpty();
        RequestContext.set(context.getBean(LocalRuntimeIdentity.class).newRequestContext());
        var service = context.getBean(LocalConnectionService.class);
        try {
            String manifest=System.getenv("DATA_AGENT_WORKBENCH_FIXTURES");
            if (manifest==null || manifest.isBlank()) throw new IllegalArgumentException("Supply DATA_AGENT_WORKBENCH_FIXTURES and existing encrypted local test connections; PG import is retired");
            var fixtures=LocalConnectionRepository.JSON.readValue(Files.readAllBytes(Path.of(manifest)),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>() {});
            if (fixtures.size()!=2) throw new IllegalArgumentException("Expected the two authorized local MySQL / DM fixtures");
            var types=new HashSet<String>();
            for (var fixture:fixtures) {
                if (!(fixture.get("connectionId") instanceof Number id) || !(fixture.get("dbType") instanceof String type)
                        || !Set.of("mysql","dm").contains(type) || !types.add(type)) throw new IllegalArgumentException("Invalid local fixture manifest");
                var saved=service.get(id.longValue());
                if (!saved.dbType().equals(type) || !Set.of("localhost","127.0.0.1").contains(saved.host())
                        || saved.port()!=(type.equals("mysql")?3306:25236)) throw new IllegalArgumentException("Fixture must reference the authorized local test connection");
                summary.add(new HashMap<>(Map.of("connectionId",saved.id(),"dbType",type,
                        "catalog",type.equals("mysql")?"sys_demo":"","schema",type.equals("dm")?"ERP_DEMO":"")));
            }
        } finally { RequestContext.clear(); }
    }
    @AfterAll static void close() throws Exception {
        if (context != null) context.close();
        if (!summary.isEmpty()) Files.write(directory.getParent().resolve("vc04-live-fixtures.json"), LocalConnectionRepository.JSON.writeValueAsBytes(summary));
    }
    static com.fasterxml.jackson.databind.JsonNode call(String method, String path, Object body, int expected) throws Exception {
        var identity = context.getBean(LocalRuntimeIdentity.class);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path))
                .header("Authorization", "Bearer " + identity.transportTokenForHost()).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(LocalConnectionRepository.JSON.writeValueAsBytes(body)));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP status for %s", path).isEqualTo(expected);
        return LocalConnectionRepository.JSON.readTree(response.body()).path("data");
    }
    @Test void bothEnginesSupportMetadataPreviewAndReadOnlyConsoleWithoutApplicationDatabaseBeans() throws Exception {
        assertThat(call("GET", "/api/runtime/capabilities", null, 200).path("features").path("databaseWorkbench").path("state").asText()).isEqualTo("READY");
        for (var fixture : summary) {
            long id = ((Number) fixture.get("connectionId")).longValue();
            String type = (String) fixture.get("dbType"), catalog = (String) fixture.get("catalog"), schema = (String) fixture.get("schema");
            call("POST", "/api/connections/open?connectionId=" + id, null, 200);
            var databases = call("GET", "/api/databases?connectionId=" + id, null, 200);
            assertThat(databases.isArray()).isTrue();
            String scope = "connectionId=" + id + "&catalog=" + URLEncoder.encode(catalog, java.nio.charset.StandardCharsets.UTF_8)
                    + "&schema=" + URLEncoder.encode(schema, java.nio.charset.StandardCharsets.UTF_8);
            var tables = call("GET", "/api/tables?" + scope, null, 200); assertThat(tables.isArray() && !tables.isEmpty()).isTrue();
            String table = java.util.stream.StreamSupport.stream(tables.spliterator(), false).map(com.fasterxml.jackson.databind.JsonNode::asText)
                    .filter(name -> !name.toLowerCase().matches(".*(user|account|secret|credential|password).*"))
                    .findFirst().orElseThrow();
            fixture.put("table", table);
            call("GET", "/api/columns?" + scope + "&tableName=" + URLEncoder.encode(table, java.nio.charset.StandardCharsets.UTF_8), null, 200);
            call("GET", "/api/tables/data?" + scope + "&tableName=" + URLEncoder.encode(table, java.nio.charset.StandardCharsets.UTF_8) + "&pageSize=2", null, 200);
            String quote = type.equals("dm") ? "\"" : "`";
            String qualifier = type.equals("dm") ? schema : catalog;
            String sql = "SELECT COUNT(*) AS ROW_COUNT FROM " + quote + qualifier.replace(quote, quote + quote) + quote
                    + "." + quote + table.replace(quote, quote + quote) + quote;
            fixture.put("sql", sql);
            var payload = new HashMap<String, Object>(); payload.put("connectionId", id); payload.put("catalog", catalog);
            payload.put("schema", schema); payload.put("sql", sql); payload.put("maxRows", 10); payload.put("timeoutMs", 10000);
            var result = call("POST", "/api/db/sql/execute", payload, 200);
            assertThat(result.path("success").asBoolean()).isTrue();
            fixture.put("rowCount", result.path("rows").path(0).path(0).asLong());
            // No write-shaped requests or negative DML probes are sent to a real database.
            // Those are exercised separately against mocks, before any JDBC call can occur.
        }
    }
}
