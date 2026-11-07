package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.*;
import edu.zsc.ai.DataAgentApplication;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.service.db.TableService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
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
 * T4 end-to-end interop: the REAL T2 durable prototype worker (pi-durable 1.5.0, sqlite
 * storage) driven by the real Java host over HTTP/SSE, with the model pointed at the local
 * scripted fixture server (pi-durable-fixture-server.mjs). No mock worker, no real model
 * vendor, no business database.
 *
 * Two integration shims, both test-only:
 *  - the worker is spawned through a generated wrapper that sets DURABLE_STORAGE /
 *    DURABLE_SQLITE_PATH and overrides the model baseUrl per modelId (on both
 *    ModelRegistry.register and the T5 recovery-path registerShell), because
 *    ModelEndpointPolicy requires an HTTPS endpoint string while the fixture speaks plain
 *    HTTP on loopback (the baseUrl is not a credential; the apiKey still flows via stdin);
 *  - fault injection SIGKILLs the worker by matching the unique wrapper path via
 *    ProcessHandle.
 */
@ExtendWith(OutputCaptureExtension.class)
class LocalPiDurableInteropTest {
    @TempDir static Path root;
    static ServletWebServerApplicationContext context;
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static String base, token, runtime, toolModelId, streamModelId;
    static long connectionId;
    static Path wrapper;
    static final TableService TABLES = mock(TableService.class);
    static final String SECRET = "DURABLE_SECRET_NEVER_PRINT";

    /** A fixture model server child process with its stdout captured line by line. */
    static final class FixtureServer {
        final Process process;
        final List<String> lines = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final String baseUrl;
        FixtureServer(Path script, String scenario) throws Exception {
            process = new ProcessBuilder("node", script.toString(), scenario).start();
            Thread reader = new Thread(() -> {
                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) { lines.add(line); queue.offer(line); }
                } catch (Exception ignored) { /* process gone */ }
            }, "fixture-stdout-" + scenario);
            reader.setDaemon(true);
            reader.start();
            long deadline = System.currentTimeMillis() + 15000;
            String found = null;
            while (System.currentTimeMillis() < deadline && found == null) {
                var line = queue.poll(200, TimeUnit.MILLISECONDS);
                if (line != null && line.startsWith("READY ")) found = line.substring(6).trim();
            }
            if (found == null) throw new IllegalStateException("fixture server did not announce READY");
            baseUrl = found;
        }
        int callCount() { return (int) lines.stream().filter(line -> line.startsWith("CALL ")).count(); }
        void awaitLine(String prefix, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (lines.stream().anyMatch(line -> line.startsWith(prefix))) return;
                Thread.sleep(50);
            }
            fail("fixture server never printed " + prefix + "; saw " + lines);
        }
        void close() { process.destroyForcibly(); }
    }
    static FixtureServer toolServer, streamServer;

    static Path diagFile;

    @Configuration(proxyBeanMethods = false) static class Fixture {
        @Bean @Primary TableService fixtureTables() {
            when(TABLES.getTables(any())).thenReturn(List.of("safe_table"));
            return TABLES;
        }
        @Bean @Primary ModelConnectionProbe fixtureConnectionProbe() {
            return new ModelConnectionProbe() { @Override public void test(ResolvedModelConfig config, String secret) { } };
        }
    }

    static boolean nodeAvailable() {
        try { return new ProcessBuilder("node", "--version").start().waitFor(5, TimeUnit.SECONDS); }
        catch (Exception e) { return false; }
    }

    /** Walk up from user.dir to find the durable prototype worker of the sibling module. */
    static Path locateDurableWorker() {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            var candidate = dir.resolve("Data-Agent-Pi-Worker/durable-prototype/src/worker.js");
            if (Files.isRegularFile(candidate)) return candidate;
            candidate = dir.resolve("durable-prototype/src/worker.js");
            if (Files.isRegularFile(candidate) && dir.endsWith("Data-Agent-Pi-Worker")) return candidate;
        }
        return null;
    }

    /** SIGKILL the worker spawned through our unique wrapper; waits until it exists. */
    static void killWorker() throws Exception {
        String marker = wrapper.toString();
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            var matches = ProcessHandle.allProcesses()
                    .filter(ph -> ph.info().commandLine().orElse("").contains(marker)).toList();
            if (!matches.isEmpty()) {
                matches.forEach(ph -> ph.destroyForcibly());
                for (var ph : matches) ph.onExit().get(10, TimeUnit.SECONDS);
                return;
            }
            Thread.sleep(100);
        }
        fail("durable worker process not found for marker " + marker);
    }

    @BeforeAll static void start() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node runtime is required for Pi worker tests");
        Path durableWorker = locateDurableWorker();
        Assumptions.assumeTrue(durableWorker != null, "durable prototype worker not found next to the repo");
        Path durableRoot = durableWorker.getParent().getParent();
        Assumptions.assumeTrue(Files.isDirectory(durableRoot.resolve("node_modules/@earendil-works/pi-durable")),
                "durable prototype dependencies not installed (npm ci in durable-prototype)");

        Path fixtureScript = Path.of("src/test/resources/pi-durable-fixture-server.mjs").toAbsolutePath();
        assertThat(fixtureScript).isRegularFile();
        toolServer = new FixtureServer(fixtureScript, "tool");
        streamServer = new FixtureServer(fixtureScript, "stream");

        // The wrapper pins durable storage to this test run's temp directory and redirects the
        // (HTTPS-only per policy) model baseUrl to the loopback fixture server per modelId.
        wrapper = root.resolve("pi-durable-test-wrapper.mjs");
        var baseUrls = new LinkedHashMap<String, String>();
        baseUrls.put("fixture-durable-tool", toolServer.baseUrl);
        baseUrls.put("fixture-durable-stream", streamServer.baseUrl);
        diagFile = Path.of("target/durable-interop-worker-diag.log").toAbsolutePath();
        // Append-only across runs: a flake's worker stderr must survive for forensics.
        Files.writeString(diagFile, "\n=== RUN " + System.currentTimeMillis() + " ===\n",
                StandardCharsets.UTF_8, Files.exists(diagFile)
                        ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
        Files.writeString(wrapper, "import fs from 'node:fs';\n"
                + "const diag = fs.createWriteStream(" + JSON.writeValueAsString(diagFile.toString()) + ", { flags: 'a' });\n"
                + "diag.write(`BOOT ${Date.now()} pid=${process.pid}\\n`);\n"
                + "const origErr = process.stderr.write.bind(process.stderr);\n"
                + "process.stderr.write = (chunk, ...args) => { try { diag.write('ERR ' + chunk); } catch {} return origErr(chunk, ...args); };\n"
                + "const origOut = process.stdout.write.bind(process.stdout);\n"
                + "process.stdout.write = (chunk, ...args) => { try { diag.write(`OUT ${Date.now()} ` + String(chunk).slice(0, 600) + '\\n'); } catch {} return origOut(chunk, ...args); };\n"
                + "process.env.DURABLE_STORAGE = 'sqlite';\n"
                + "process.env.DURABLE_SQLITE_PATH = " + JSON.writeValueAsString(root.resolve("durable.sqlite").toString()) + ";\n"
                + "const baseUrls = " + JSON.writeValueAsString(baseUrls) + ";\n"
                + "const { ModelRegistry } = await import(" + JSON.writeValueAsString(
                        durableRoot.resolve("src/models.js").toUri().toURL().toString()) + ");\n"
                // T5: recovery registers the provider via registerShell() BEFORE hello, and the
                // registry's first registration for a modelId wins, so the baseUrl override must
                // cover both entry points or a recovered run would call the fake policy endpoint.
                + "const overrideModel = (model) => {\n"
                + "  const override = baseUrls[model && model.modelId];\n"
                + "  return override ? { ...model, baseUrl: override } : model;\n"
                + "};\n"
                + "for (const method of ['register', 'registerShell']) {\n"
                + "  const original = ModelRegistry.prototype[method];\n"
                + "  ModelRegistry.prototype[method] = function (model, ...rest) {\n"
                + "    return original.call(this, overrideModel(model), ...rest);\n"
                + "  };\n"
                + "}\n"
                + "await import(" + JSON.writeValueAsString(durableWorker.toUri().toURL().toString()) + ");\n");

        context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(DataAgentApplication.class, Fixture.class).run(
                "--app.runtime.mode=local", "--server.port=0", "--spring.main.banner-mode=off", "--app.local.data-directory=" + root,
                "--app.local.pi.enabled=true", "--app.local.pi.worker-script=" + wrapper,
                "--app.local.pi.startup-timeout-ms=30000", "--app.local.pi.cancel-grace-seconds=3");
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
        var identity = context.getBean(LocalRuntimeIdentity.class);
        token = identity.transportTokenForHost();
        runtime = identity.runtimeInstanceId().toString();
        toolModelId = registerModel("Durable tool", "fixture-durable-tool", true);
        streamModelId = registerModel("Durable stream", "fixture-durable-stream", false);
        Path driver = Files.write(root.resolve("fixture-driver.jar"), new byte[0]);
        var connection = call("POST", "/api/connections/create", Map.of("name", "Durable db", "dbType", "mysql",
                "host", "example.invalid", "port", 3306, "username", "fixture", "driverJarPath", driver.toString()), true);
        connectionId = JSON.readTree(connection.body()).path("data").path("id").asLong();
    }
    @AfterAll static void close() {
        if (context != null) context.close();
        if (toolServer != null) toolServer.close();
        if (streamServer != null) streamServer.close();
    }

    static String registerModel(String name, String modelId, boolean makeDefault) throws Exception {
        var setup = new LinkedHashMap<String, Object>();
        setup.put("name", name); setup.put("provider", "CUSTOM"); setup.put("apiStyle", "OPENAI_CHAT_COMPLETIONS");
        setup.put("endpoint", "https://fixture-durable.invalid/v1/"); setup.put("compatibilityProfile", "openai-chat-v1");
        setup.put("apiKey", SECRET); setup.put("modelId", modelId); setup.put("capabilityProfile", "text-tools-v1");
        setup.put("defaultOptions", Map.of("thinkingMode", "DISABLED", "maxOutputTokens", 2048));
        setup.put("maxContextTokens", 32768); setup.put("memoryThreshold", 4000);
        setup.put("enabled", true); setup.put("chatVisible", true); setup.put("makeDefault", makeDefault);
        var versions = new LinkedHashMap<String, Integer>();
        for (var purpose : ModelPurpose.values()) versions.put(purpose.name(), 0);
        setup.put("bindingVersions", versions);
        var saved = call("POST", "/api/ai/model-setups", setup, true);
        return JSON.readTree(saved.body()).path("data").path("model").path("id").asText();
    }

    static HttpRequest request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("X-Local-Runtime-Id", runtime);
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }
    static HttpResponse<String> call(String method, String path, Object body, boolean checked) throws Exception {
        var response = HTTP.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
        if (checked) assertThat(response.statusCode()).as(path + " -> %s", response.body()).isEqualTo(200);
        return response;
    }
    static long session(boolean database) throws Exception {
        var created = call("POST", "/api/local/conversations",
                database ? Map.of("connectionId", connectionId, "catalog", "fixture_db") : Map.of(), true);
        return JSON.readTree(created.body()).path("data").path("conversationId").asLong();
    }
    static String piTurn(long id, String text, String modelConfigId) throws Exception {
        return call("POST", "/api/local/pi/chat/stream",
                Map.of("conversationId", id, "message", text, "modelConfigId", modelConfigId), true).body();
    }
    static List<JsonNode> blocks(String sse) throws Exception {
        var result = new ArrayList<JsonNode>();
        for (String line : sse.split("\n")) if (line.startsWith("data:")) result.add(JSON.readTree(line.substring(5).trim()));
        return result;
    }
    static long countBlocks(List<JsonNode> blocks, String type) {
        return blocks.stream().filter(block -> block.path("type").asText().equals(type)).count();
    }

    /** The worker's stdout+stderr tee must never contain the apiKey (it travels stdin-only). */
    static void assertWorkerChannelsClean() throws Exception {
        if (Files.isRegularFile(diagFile))
            assertThat(Files.readString(diagFile)).as("worker stdout/stderr free of apiKey").doesNotContain(SECRET);
    }

    // -----------------------------------------------------------------------
    // Real interop: start -> tool_call -> Java executes -> tool_result -> text -> SUCCEEDED
    // -----------------------------------------------------------------------
    @Test void realDurableWorkerCompletesToolLoop(CapturedOutput output) throws Exception {
        long id = session(true);
        clearInvocations(TABLES);
        when(TABLES.getTables(any())).thenReturn(List.of("orders_table"));
        int callsBefore = toolServer.callCount();

        String body = piTurn(id, "列出所有表", toolModelId);
        var blocks = blocks(body);

        assertThat(body).contains("SUCCEEDED", "durable tool loop completed");
        assertThat(blocks.stream().filter(block -> block.path("done").asBoolean())).hasSize(1);
        assertThat(countBlocks(blocks, "TOOL_CALL")).isEqualTo(1);
        assertThat(countBlocks(blocks, "TOOL_RESULT")).isEqualTo(1);
        assertThat(blocks.stream().map(block -> block.path("type").asText()).toList())
                .containsSubsequence("STATUS", "TOOL_CALL", "TOOL_RESULT", "TEXT");
        verify(TABLES, timeout(10000).times(1)).getTables(any());
        // Exactly two model calls: one that asked for the tool, one continuation after tool_result.
        assertThat(toolServer.callCount() - callsBefore).isEqualTo(2);
        assertThat(output).contains("protocol=2").contains("storage=sqlite").contains("recoveredRuns=0");
        assertThat(body).doesNotContain(SECRET);
        assertThat(output).doesNotContain(SECRET);
        assertWorkerChannelsClean();
    }

    // -----------------------------------------------------------------------
    // Fault injection: SIGKILL while Java holds the tool execution. The run must
    // recover via start(idempotencyKey) + attach, the tool executes exactly once,
    // and exactly one finished (SUCCEEDED) is delivered.
    // -----------------------------------------------------------------------
    @Test void sigkillDuringToolWaitRecoversAndExecutesToolOnce(CapturedOutput output) throws Exception {
        long id = session(true);
        clearInvocations(TABLES);
        var toolStarted = new CountDownLatch(1);
        var workerKilled = new CountDownLatch(1);
        when(TABLES.getTables(any())).thenAnswer(invocation -> {
            toolStarted.countDown();
            assertThat(workerKilled.await(30, TimeUnit.SECONDS)).as("worker killed while tool blocked").isTrue();
            return List.of("orders_table");
        });
        int callsBefore = toolServer.callCount();

        var pending = CompletableFuture.supplyAsync(() -> {
            try { return piTurn(id, "列出所有表", toolModelId); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertThat(toolStarted.await(30, TimeUnit.SECONDS)).as("tool_call reached Java").isTrue();
        killWorker(); // SIGKILL mid tool wait; the durable run survives in sqlite
        workerKilled.countDown();

        String body = pending.get(90, TimeUnit.SECONDS);
        var blocks = blocks(body);
        assertThat(body).contains("SUCCEEDED", "durable tool loop completed");
        assertThat(blocks.stream().filter(block -> block.path("done").asBoolean())).hasSize(1);
        // The recovered worker re-emitted the same tool_call; Java answered from the
        // run-scoped cache: one SSE pair, one underlying execution.
        assertThat(countBlocks(blocks, "TOOL_CALL")).isEqualTo(1);
        assertThat(countBlocks(blocks, "TOOL_RESULT")).isEqualTo(1);
        verify(TABLES, timeout(10000).times(1)).getTables(any());
        assertThat(toolServer.callCount() - callsBefore).isEqualTo(2);
        assertThat(output).contains("scheduling durable reattach")
                .contains("Pi run reattached after worker restart")
                .containsPattern(Pattern.compile("recoveredRuns=[1-9]"));
        assertThat(body).doesNotContain(SECRET);
        assertThat(output).doesNotContain(SECRET);
        assertWorkerChannelsClean();
    }

    // -----------------------------------------------------------------------
    // Fault injection mid text stream: the killed generation attempt is retried by
    // pi-durable; the host observes TEXT_RESET and then the full recovered answer.
    // -----------------------------------------------------------------------
    @Test void sigkillMidTextStreamEmitsTextResetAndRecovers(CapturedOutput output) throws Exception {
        long id = session(false);
        int callsBefore = streamServer.callCount();

        var pending = CompletableFuture.supplyAsync(() -> {
            try { return piTurn(id, "讲个长故事", streamModelId); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        streamServer.awaitLine("STREAMED 20", 30000);
        Thread.sleep(500); // let the worker forward the partial text to Java before the kill
        killWorker();

        String body = pending.get(90, TimeUnit.SECONDS);
        var blocks = blocks(body);
        assertThat(body).contains("SUCCEEDED");
        assertThat(blocks.stream().filter(block -> block.path("done").asBoolean())).hasSize(1);
        var resets = blocks.stream()
                .filter(block -> block.path("type").asText().equals("STATUS") && block.path("data").asText().equals("TEXT_RESET"))
                .toList();
        assertThat(resets).as("TEXT_RESET observed at least once").isNotEmpty();
        int resetAt = blocks.indexOf(resets.get(0));
        var text = new StringBuilder();
        for (var block : blocks.subList(resetAt + 1, blocks.size()))
            if (block.path("type").asText().equals("TEXT")) text.append(block.path("data").asText());
        assertThat(text.toString()).isEqualTo("recovered final answer");
        assertThat(streamServer.callCount() - callsBefore).isEqualTo(2);
        assertThat(output).contains("Pi text reset");
        assertThat(body).doesNotContain(SECRET);
        assertThat(output).doesNotContain(SECRET);
        assertWorkerChannelsClean();
    }
}
