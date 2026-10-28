package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.zsc.ai.DataAgentApplication;
import edu.zsc.ai.api.model.request.BaseRequest;
import edu.zsc.ai.context.AgentExecutionContext;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.context.RequestContextInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRuntimeHttpTest {
    @TempDir static Path directory;
    static ServletWebServerApplicationContext context;
    static String base;
    static LocalRuntimeIdentity identity;
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll static void start() {
        context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(
                DataAgentApplication.class, TestEndpoints.class).run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--app.local.data-directory=" + directory.toAbsolutePath(),
                "--app.local.allowed-origins=http://localhost:5173",
                "--logging.config=classpath:local-runtime-logback.xml");
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
        identity = context.getBean(LocalRuntimeIdentity.class);
    }

    @AfterAll static void stop() { if (context != null) context.close(); }

    static HttpRequest.Builder request(String path, boolean authenticated) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(10));
        if (authenticated) builder.header("Authorization", "Bearer " + identity.transportTokenForHost());
        return builder;
    }

    static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void assertError(HttpResponse<String> response, int status, String reason) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode json = JSON.readTree(response.body());
        assertThat(json.path("code").asInt()).isEqualTo(status);
        assertThat(json.path("reasonCode").asText()).isEqualTo(reason);
        assertThat(json.get("data").isNull()).isTrue();
        assertThat(response.body()).doesNotContain(identity.transportTokenForHost(), directory.toString());
    }

    @Test void startsOnLoopbackWithoutLegacyAccountOrApplicationDatabase() {
        var tomcat = (TomcatWebServer) context.getWebServer();
        var address = (java.net.InetAddress) tomcat.getTomcat().getConnector().getProperty("address");
        assertThat(address.isLoopbackAddress()).isTrue();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        for (String legacyType : java.util.List.of(
                "edu.zsc.ai.domain.service.sys.SysSessionsService",
                "edu.zsc.ai.domain.service.org.OrgAccessService",
                "edu.zsc.ai.config.sys.SaTokenConfigure",
                "org.apache.ibatis.session.SqlSessionFactory")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Class.forName(legacyType))
                    .isInstanceOf(ClassNotFoundException.class);
        }
        assertThat(context.getBeansOfType(cn.dev33.satoken.config.SaTokenConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(edu.zsc.ai.domain.service.ai.model.FileModelStore.class)).hasSize(1);
        assertThat(context.getBeansOfType(edu.zsc.ai.domain.service.ai.model.FileCredentialStore.class)).hasSize(1);
        assertThat(context.getBean(edu.zsc.ai.config.ai.model.MultiProviderProperties.class).getModelRouting().getStorageDirectory())
                .isEqualTo(directory.resolve("models").toString());
        assertThat(directory.resolve("connections.json")).doesNotExist();
        assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isNull();
        assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Class.forName("org.postgresql.Driver")).isInstanceOf(ClassNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Class.forName("dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore")).isInstanceOf(ClassNotFoundException.class);
    }

    @Test void capabilitiesAreHonestAndContainNoSecretsOrDataPaths() throws Exception {
        var response = send(request("/api/runtime/capabilities", true));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        JsonNode root = JSON.readTree(response.body());
        assertThat(root.path("code").asInt()).isEqualTo(200);
        assertThat(root.path("message").asText()).isEqualTo("ok");
        JsonNode data = root.path("data");
        assertThat(data.path("mode").asText()).isEqualTo("LOCAL");
        assertThat(data.path("contractVersion").asInt()).isEqualTo(1);
        assertThat(data.path("runtimeInstanceId").asText()).isEqualTo(identity.runtimeInstanceId().toString());
        for (String field : java.util.List.of("accountLoginEnabled", "organizationEnabled", "chatHistoryPersistenceEnabled")) {
            assertThat(data.path(field).booleanValue()).isFalse();
        }
        assertThat(data.path("features").size()).isEqualTo(4);
        assertThat(data.path("features").path("databaseWorkbench").path("state").asText()).isEqualTo("READY");
        assertThat(data.path("features").path("databaseWorkbench").path("reasonCode").isNull()).isTrue();
        assertThat(data.path("features").path("aiAssistant").path("state").asText()).isEqualTo("UNCONFIGURED");
        assertThat(data.path("features").path("aiAssistant").path("reasonCode").asText()).isEqualTo("MODEL_NOT_CONFIGURED");
        for (String name : java.util.List.of("knowledgeMemory", "skills")) {
            var feature = data.path("features").path(name);
            assertThat(feature.path("state").asText()).isEqualTo("NOT_IMPLEMENTED");
            assertThat(feature.path("reasonCode").asText()).isEqualTo("FEATURE_NOT_IMPLEMENTED");
        }
        assertThat(response.body()).doesNotContain(identity.transportTokenForHost(), directory.toString());
    }

    @Test void missingWrongAndPreviousProcessCredentialsAreRejected() throws Exception {
        assertError(send(request("/api/runtime/capabilities", false)), 401, "LOCAL_CLIENT_UNAUTHENTICATED");
        assertError(send(request("/api/runtime/capabilities", false).header("Authorization", "Bearer wrong")),
                401, "LOCAL_CLIENT_UNAUTHENTICATED");
        var anotherProcess = new LocalRuntimeIdentity();
        assertThat(anotherProcess.runtimeInstanceId()).isNotEqualTo(identity.runtimeInstanceId());
        assertError(send(request("/api/runtime/capabilities", false)
                .header("Authorization", "Bearer " + anotherProcess.transportTokenForHost())),
                401, "LOCAL_CLIENT_UNAUTHENTICATED");
    }

    @Test void duplicateAuthorizationIsRejected() throws Exception {
        assertError(send(request("/api/runtime/capabilities", true).header("Authorization", "Bearer other")),
                401, "LOCAL_CLIENT_UNAUTHENTICATED");
    }

    @Test void anotherStartupRotatesInstanceAndRejectsPreviousToken() throws Exception {
        try (var restarted = (ServletWebServerApplicationContext) new SpringApplicationBuilder(DataAgentApplication.class).run(
                "--app.runtime.mode=local", "--server.port=0", "--spring.main.banner-mode=off",
                "--app.local.data-directory=" + directory.toAbsolutePath(),
                "--logging.config=classpath:local-runtime-logback.xml")) {
            var next = restarted.getBean(LocalRuntimeIdentity.class);
            assertThat(next.runtimeInstanceId()).isNotEqualTo(identity.runtimeInstanceId());
            String endpoint = "http://127.0.0.1:" + restarted.getWebServer().getPort() + "/api/runtime/capabilities";
            var old = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Authorization", "Bearer " + identity.transportTokenForHost());
            assertError(send(old), 401, "LOCAL_CLIENT_UNAUTHENTICATED");
            var valid = HttpRequest.newBuilder(URI.create(endpoint)).header("Authorization", "Bearer " + next.transportTokenForHost());
            assertThat(send(valid).statusCode()).isEqualTo(200);
            // The default development origin is 127.0.0.1:5174, not the first runtime's custom origin.
            assertError(send(HttpRequest.newBuilder(URI.create(endpoint)).header("Authorization", "Bearer " + next.transportTokenForHost())
                    .header("Origin", "http://localhost:5173")), 403, "LOCAL_SCOPE_FORBIDDEN");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"http://evil.example", "null", "http://localhost:51730"})
    void hostileOriginsAreRejectedEvenWithValidToken(String origin) throws Exception {
        assertError(send(request("/api/runtime/capabilities", true).header("Origin", origin)), 403, "LOCAL_SCOPE_FORBIDDEN");
    }

    @Test void allowedBrowserOriginCanConnectWithoutHostCredentials() throws Exception {
        var response = send(request("/api/runtime/capabilities", false).header("Origin", "http://localhost:5173"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
    }

    @Test void preflightOnlyChecksOriginAndNeverRunsBusinessHandlers() throws Exception {
        var response = send(request("/api/auth/login", false).header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type").method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertError(send(request("/api/runtime/capabilities", false).header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "TRACE").method("OPTIONS", HttpRequest.BodyPublishers.noBody())),
                403, "LOCAL_SCOPE_FORBIDDEN");
    }

    @ParameterizedTest @ValueSource(strings = {"X-User-Id", "X-Owner-Id", "X-Org-Id", "X-Org-User-Rel-Id", "X-Org-Role"})
    void foreignIdentityHeadersCannotExpandScope(String header) throws Exception {
        assertError(send(request("/api/runtime/capabilities", true).header(header, "42")), 403, "LOCAL_SCOPE_FORBIDDEN");
    }

    @Test void orgHeaderAndQueryAreRejected() throws Exception {
        assertError(send(request("/api/runtime/capabilities", true).header("X-Workspace-Type", "ORGANIZATION")),
                403, "LOCAL_SCOPE_FORBIDDEN");
        assertError(send(request("/api/runtime/capabilities?clientOrgId=42", true)), 403, "LOCAL_SCOPE_FORBIDDEN");
        assertError(send(request("/api/runtime/capabilities?userId=1&userId=42", true)), 403, "LOCAL_SCOPE_FORBIDDEN");
    }

    @ParameterizedTest @ValueSource(strings = {"{\"userId\":42}", "{\"ownerId\":42}", "{\"clientOrgId\":42}",
            "{\"clientWorkspaceType\":\"ORGANIZATION\"}", "{\"orgRole\":\"ADMIN\"}", "{\"orgId\":{\"id\":42}}"})
    void bodyHintsAreRejectedBeforeUnknownDtoFieldsAreDiscarded(String body) throws Exception {
        assertError(send(request("/vc02-test/echo", true).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))), 403, "LOCAL_SCOPE_FORBIDDEN");
    }

    @ParameterizedTest @ValueSource(strings = {"{", "{\"userId\":42,\"userId\":1}", "{} {}"})
    void malformedOrAmbiguousJsonIsRejected(String body) throws Exception {
        assertError(send(request("/vc02-test/echo", true).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))), 400, "LOCAL_REQUEST_INVALID");
    }

    @Test void baseContextComesFromApplicationAndHasNoOrganization() throws Exception {
        var response = send(request("/vc02-test/echo", true).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":1,\"clientWorkspaceType\":\"PERSONAL\",\"connectionId\":99}")));
        JsonNode result = JSON.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result.path("userId").asLong()).isEqualTo(1L);
        assertThat(result.path("workspaceType").asText()).isEqualTo("PERSONAL");
        assertThat(result.path("orgId").isNull()).isTrue();
        assertThat(result.path("orgUserRelId").isNull()).isTrue();
        assertThat(result.path("orgRole").isNull()).isTrue();
        // A submitted connection ID is not authorization or an automatically trusted execution target.
        assertThat(result.path("connectionId").isNull()).isTrue();
    }

    @Test void mvcAsyncWorkReceivesIdentityAndWorkerIsCleanAfterwards() throws Exception {
        var response = send(request("/vc02-test/async", true));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("userId").asLong()).isEqualTo(1L);
        var executor = context.getBean("localExecutor", ThreadPoolTaskExecutor.class);
        assertThat(executor.submit(() -> RequestContext.hasContext() || AgentRequestContext.hasContext()
                || AgentExecutionContext.getTaskId() != null).get(5, TimeUnit.SECONDS)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"/api/auth/login", "/api/ai/chat", "/api/runtime/token"})
    void unportedRoutesNeverReachLegacyServicesOrIssueTokens(String route) throws Exception {
        assertError(send(request(route, true)), 409, "LOCAL_FEATURE_NOT_SUPPORTED");
    }

    @RestController
    static class TestEndpoints {
        @PostMapping("/vc02-test/echo") RequestContextInfo echo(@RequestBody BaseRequest ignored) { return RequestContext.snapshot(); }
        @GetMapping("/vc02-test/async") Callable<RequestContextInfo> async() { return RequestContext::snapshot; }
    }
}
