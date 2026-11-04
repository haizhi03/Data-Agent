package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.*;
import edu.zsc.ai.agent.memory.*;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.QwenProperties;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.entity.ai.StoredChatMessage;
import edu.zsc.ai.domain.service.ai.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static edu.zsc.ai.config.ai.model.ProtocolFixtures.*;

@Timeout(20)
class ProtocolHttpContractTest {
    @TempDir static Path temp;
    private static SSLContext tls;
    private static X509TrustManager trust;
    private HttpsServer server;
    private ExecutorService executor;
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<com.sun.net.httpserver.Headers> requestHeaders = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private CredentialService credentials;

    @BeforeAll static void certificate() throws Exception {
        Path store = temp.resolve("fixture.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=127.0.0.1",
                "-ext", "SAN=ip:127.0.0.1", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "fixture-password", "-noprompt")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "fixture-password".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, "fixture-password".toCharArray());
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trusts.init(keys);
        trust = (X509TrustManager) trusts.getTrustManagers()[0];
        tls = SSLContext.getInstance("TLS"); tls.init(managers.getKeyManagers(), new TrustManager[]{trust}, null);
    }

    @BeforeEach void start() throws Exception {
        QwenProperties qwen = new QwenProperties(); qwen.setApiKey("fixture-key"); credentials = new CredentialService(new MockEnvironment(), qwen);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setHttpsConfigurator(new HttpsConfigurator(tls));
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/gateway/v1/", exchange -> {
            try {
                requests.add(ProtocolJson.parse(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                requestHeaders.add(exchange.getRequestHeaders());
                assertTrue(exchange.getRequestURI().getPath().endsWith("/responses") || exchange.getRequestURI().getPath().endsWith("/messages"));
                Reply reply = replies.poll(5, TimeUnit.SECONDS); if (reply == null) throw new AssertionError("Unexpected upstream request");
                exchange.getResponseHeaders().set("Content-Type", reply.contentType());
                if (reply.status() == 302) exchange.getResponseHeaders().set("Location", "https://unapproved.example/credential-leak");
                exchange.sendResponseHeaders(reply.status(), 0);
                exchange.getResponseBody().write(reply.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
                if (reply.written() != null) reply.written().countDown();
                if (reply.release() != null) assertTrue(reply.release().await(5, TimeUnit.SECONDS));
            } catch (Throwable error) { serverFailure.compareAndSet(null, error); }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() {
        RequestContext.clear(); AgentRequestContext.clear();
        server.stop(0); executor.shutdownNow();
        assertNull(serverFailure.get(), () -> "Fixture server failed: " + serverFailure.get());
    }

    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void l1TextAndL2StreamingPreserveProxyPrefixUsageAndAuthentication(ModelApiStyle style) throws Exception {
        var config = config(style); var model = model(config);
        ObjectNode response = response(style, text(style, "Hello"), "answer-1");
        replies.add(json(response));
        ChatResponse direct = model.chat(request());
        assertEquals("Hello", direct.aiMessage().text()); assertEquals(18, direct.tokenUsage().totalTokenCount());
        JsonNode encoded = requests.poll(2, TimeUnit.SECONDS); assertEquals("fixture-model", encoded.path("model").asText());
        var headers = requestHeaders.poll(2, TimeUnit.SECONDS);
        if (style == ModelApiStyle.OPENAI_RESPONSES) {
            assertEquals("Bearer fixture-key", headers.getFirst("Authorization")); assertFalse(encoded.path("store").asBoolean(true));
        } else { assertEquals("fixture-key", headers.getFirst("x-api-key")); assertEquals("2023-06-01", headers.getFirst("anthropic-version")); }
        replies.add(sse(stream(style, response, true)));
        Result result = invoke(model, request());
        ChatResponse streamed = result.response().get(5, TimeUnit.SECONDS);
        assertEquals("Hello", result.text().toString()); assertEquals(18, streamed.tokenUsage().totalTokenCount());
        assertEquals(0, result.tools().get()); assertEquals(0, result.thoughts().get());
    }

    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void interleavedToolsKeepSeparateArgumentsAndOnlyPersistCompleteOpaqueState(ModelApiStyle style) throws Exception {
        String nonce = UUID.randomUUID().toString(); ArrayNode blocks = stateAndCalls(style, nonce, true);
        ObjectNode response = response(style, blocks, "tools-1"); replies.add(sse(stream(style, response, true)));
        var result = invoke(model(config(style)), request()); var completed = result.response().get(5, TimeUnit.SECONDS);
        assertEquals(2, result.tools().get()); assertEquals(2, completed.aiMessage().toolExecutionRequests().size());
        for (var call : completed.aiMessage().toolExecutionRequests()) assertEquals(nonce, ProtocolJson.parse(call.arguments()).path("nonce").asText());
        AiMessage restored = (AiMessage) ChatMessageDeserializer.messageFromJson(ChatMessageSerializer.messageToJson(completed.aiMessage()));
        assertEquals(blocks, ProtocolStateCodec.blocks(restored, config(style)));
        assertFalse(restored.attributes().get(ProtocolStateCodec.ATTRIBUTE).toString().contains("incomplete-encrypted-state"));
        assertEquals(0, result.thoughts().get()); // Signatures and raw thinking are never UI deltas.
    }

    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void l3AndL4AgentExecutesEchoOnceAndResumesAfterPersistentMemoryReconstruction(ModelApiStyle style) throws Exception {
        String nonce = UUID.randomUUID().toString(); Echo echo = new Echo(nonce);
        AtomicReference<List<StoredChatMessage>> persisted = new AtomicReference<>(List.of());
        AiMessageService messages = mock(AiMessageService.class); AiConversationService conversations = mock(AiConversationService.class);
        ChatMemoryCompressor compressor = mock(ChatMemoryCompressor.class);
        when(compressor.compressIfNeeded(anyLong(), anyString(), anyList())).thenAnswer(call -> call.getArgument(2));
        when(messages.getActiveByConversationIdOrderByCreatedAtAsc(9L)).thenAnswer(call -> persisted.get());
        doAnswer(call -> {
            List<ChatMessage> history = call.getArgument(1);
            persisted.set(history.stream().map(m -> StoredChatMessage.builder().conversationId(9L).role(m.type().name()).data(ChatMessageSerializer.messageToJson(m)).build()).toList());
            return null;
        }).when(messages).replaceConversationMessages(eq(9L), anyList());
        var config = config(style);
        ArrayNode opaque = stateAndCalls(style, nonce, false);
        replies.add(json(response(style, opaque, "tools-1")));
        replies.add(json(response(style, text(style, "Result: " + nonce), "answer-1")));
        ProbeAgent first = agent(model(config), new CustomChatMemoryStore(messages, conversations, compressor), echo);
        assertEquals("Result: " + nonce, first.ask("1:9:config", "Run the echo probe"));
        assertEquals(1, echo.calls.get());
        requests.poll(2, TimeUnit.SECONDS); JsonNode continuation = requests.poll(2, TimeUnit.SECONDS);
        assertContinuation(style, opaque, nonce, continuation);
        replies.add(json(response(style, text(style, "Restored"), "answer-2")));
        ProbeAgent reconstructed = agent(model(config), new CustomChatMemoryStore(messages, conversations, compressor), echo);
        assertEquals("Restored", reconstructed.ask("1:9:config", "Continue from saved history"));
        assertEquals(1, echo.calls.get());
        assertContinuation(style, opaque, nonce, requests.poll(2, TimeUnit.SECONDS));
        assertTrue(persisted.get().stream().anyMatch(row -> row.getData().contains(ProtocolStateCodec.ATTRIBUTE)));
        verify(conversations, atLeastOnce()).checkAccess(1L, 9L);
    }

    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void disconnectTruncationRefusalAndErrorNeverReleaseTools(ModelApiStyle style) throws Exception {
        var config = config(style); var model = model(config); ArrayNode blocks = stateAndCalls(style, "nonce", false);
        replies.add(sse(stream(style, response(style, blocks, "broken"), false)));
        Result disconnected = invoke(model, request()); assertError(disconnected, "MODEL_PROTOCOL_ERROR"); assertEquals(0, disconnected.tools().get());
        ObjectNode truncated = response(style, blocks, "truncated");
        if (style == ModelApiStyle.OPENAI_RESPONSES) {
            truncated.putObject("incomplete_details").put("reason", "max_output_tokens");
            replies.add(sse(event("response.incomplete", ProtocolJson.object().set("response", truncated.put("status", "incomplete")))));
        } else { truncated.put("stop_reason", "max_tokens"); replies.add(sse(stream(style, truncated, true))); }
        Result incomplete = invoke(model, request()); assertError(incomplete, "MODEL_OUTPUT_TRUNCATED"); assertEquals(0, incomplete.tools().get());
        ObjectNode refused = response(style, text(style, ""), "refused");
        if (style == ModelApiStyle.OPENAI_RESPONSES) ((ArrayNode) refused.path("output").get(0).path("content")).set(0, ProtocolJson.object().put("type", "refusal").put("refusal", "declined"));
        else refused.put("stop_reason", "refusal");
        replies.add(json(refused)); assertEquals("MODEL_REFUSED", assertThrows(ModelIntegrationException.class, () -> model.chat(request())).getReason());
        replies.add(sse(event("error", style == ModelApiStyle.OPENAI_RESPONSES ? ProtocolJson.object().put("code", "rate_limit_exceeded")
                : ProtocolJson.object().set("error", ProtocolJson.object().put("type", "rate_limit_error")))));
        assertError(invoke(model, request()), "MODEL_RATE_LIMITED");
    }

    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void malformedCallsAndMissingSignaturesAreRejectedBeforeExecution(ModelApiStyle style) throws Exception {
        ArrayNode blocks = stateAndCalls(style, "nonce", false);
        ObjectNode call = (ObjectNode) blocks.get(blocks.size() - 1);
        if (style == ModelApiStyle.OPENAI_RESPONSES) call.put("arguments", "{\"nonce\":"); else call.put("input", "not-an-object");
        replies.add(json(response(style, blocks, "invalid")));
        assertThrows(ModelIntegrationException.class, () -> model(config(style)).chat(request()));
        ArrayNode missing = stateAndCalls(style, "nonce", false);
        ((ObjectNode) missing.get(0)).remove(style == ModelApiStyle.OPENAI_RESPONSES ? "encrypted_content" : "signature");
        replies.add(json(response(style, missing, "invalid-state")));
        assertThrows(ModelIntegrationException.class, () -> model(config(style)).chat(request()));
        ArrayNode unknown = stateAndCalls(style, "nonce", false);
        ((ObjectNode) unknown.get(unknown.size() - 1)).put("name", "unregistered_tool");
        replies.add(sse(stream(style, response(style, unknown, "unknown-tool"), true)));
        Result forbidden = invoke(model(config(style)), request());
        assertError(forbidden, "TOOL_FORBIDDEN"); assertEquals(0, forbidden.tools().get());
        replies.add(sse(stream(style, response(style, stateAndCalls(style, "nonce", false), "tools-disabled"), true)));
        var disabledRequest = ChatRequest.builder().messages(request().messages()).toolSpecifications(request().toolSpecifications())
                .toolChoice(dev.langchain4j.model.chat.request.ToolChoice.NONE).build();
        Result disabled = invoke(model(config(style)), disabledRequest);
        assertError(disabled, "TOOL_FORBIDDEN"); assertEquals(0, disabled.tools().get());
    }

    @Test void actualHttpCancellationClosesOutstandingStreamAndDoesNotReleaseToolCalls() throws Exception {
        var style = ModelApiStyle.OPENAI_RESPONSES; var model = model(config(style));
        CountDownLatch written = new CountDownLatch(1), release = new CountDownLatch(1);
        replies.add(new Reply(200, "text/event-stream", event("response.created", ProtocolJson.object().set("response", ProtocolJson.object().put("id", "cancel"))), written, release));
        ModelRunSnapshot run = new ModelRunSnapshot(Map.of(), 64);
        AgentRequestContext.set(AgentRequestContextInfo.builder().modelRunSnapshot(run).build());
        Result result = invoke(model, request()); AgentRequestContext.clear();
        try {
            assertTrue(written.await(3, TimeUnit.SECONDS)); run.cancel();
            assertThrows(ExecutionException.class, () -> result.response().get(3, TimeUnit.SECONDS)); assertEquals(0, result.tools().get());
        } finally { release.countDown(); }
    }

    @Test void httpErrorsAndRedirectsAreSanitizedWithoutForwardingCredentials() {
        var model = model(config(ModelApiStyle.OPENAI_RESPONSES));
        for (int status : List.of(401, 429, 302)) {
            replies.add(new Reply(status, "application/json", "{\"secret\":\"must-not-escape\"}", null, null));
            var error = assertThrows(ModelIntegrationException.class, () -> model.chat(request()));
            assertFalse(error.toString().contains("must-not-escape")); assertNull(error.getCause());
        }
        assertEquals(3, requests.size());
    }

    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void draftConnectionProbeUsesTransientKeyAndProductionProtocolOverLocalTls(ModelApiStyle style) throws Exception {
        var config=config(style);
        var probe=new ModelConnectionProbe(){@Override protected ModelClientBundle createClients(ResolvedModelConfig c,CredentialService transientCredentials){
            var model=model(c,transientCredentials);return new ModelClientBundle(model,model);
        }};
        replies.add(json(response(style,text(style,"OK"),"draft-test")));
        assertDoesNotThrow(()->probe.test(config,"draft-fixture-key"));
        var body=requests.poll(3,TimeUnit.SECONDS);assertNotNull(body);assertEquals("fixture-model",body.path("model").asText());
        assertFalse(body.has("tools"));assertFalse(body.toString().contains("draft-fixture-key"));
        var headers=requestHeaders.poll(3,TimeUnit.SECONDS);assertNotNull(headers);
        assertEquals(style==ModelApiStyle.OPENAI_RESPONSES?"Bearer draft-fixture-key":"draft-fixture-key",
                headers.getFirst(style==ModelApiStyle.OPENAI_RESPONSES?"Authorization":"x-api-key"));
        replies.add(new Reply(401,"application/json","{\"secret\":\"must-not-escape\"}",null,null));
        var error=assertThrows(ModelIntegrationException.class,()->probe.test(config,"draft-fixture-key"));
        assertEquals("MODEL_AUTH_FAILED",error.getReason());assertNull(error.getCause());assertFalse(error.toString().contains("must-not-escape"));
    }

    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void streamingAiServicesToolLoopCompletesAndTruncatedToolsNeverExecute(ModelApiStyle style) throws Exception {
        String nonce = UUID.randomUUID().toString(); Echo echo = new Echo(nonce);
        var raw = model(config(style));
        var agent = AiServices.builder(StreamingProbeAgent.class).streamingChatModel(raw).tools(echo)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(30).build()).build();
        replies.add(sse(stream(style, response(style, stateAndCalls(style, nonce, false), "stream-tool"), true)));
        replies.add(sse(stream(style, response(style, text(style, "finished"), "stream-answer"), true)));
        CompletableFuture<ChatResponse> completed = new CompletableFuture<>();
        agent.ask("first", "echo probe").onCompleteResponse(completed::complete).onError(completed::completeExceptionally).start();
        assertEquals("finished", completed.get(5, TimeUnit.SECONDS).aiMessage().text());
        assertEquals(1, echo.calls.get());
        var truncated = response(style, stateAndCalls(style, nonce, false), "stream-truncated");
        if (style == ModelApiStyle.OPENAI_RESPONSES) {
            truncated.putObject("incomplete_details").put("reason", "max_output_tokens");
            replies.add(sse(event("response.incomplete", ProtocolJson.object().set("response", truncated))));
        } else { truncated.put("stop_reason", "max_tokens"); replies.add(sse(stream(style, truncated, true))); }
        CompletableFuture<ChatResponse> failure = new CompletableFuture<>();
        agent.ask("second", "another probe").onCompleteResponse(failure::complete).onError(failure::completeExceptionally).start();
        assertThrows(ExecutionException.class, () -> failure.get(5, TimeUnit.SECONDS));
        assertEquals(1, echo.calls.get());
    }

    @ParameterizedTest @EnumSource(value = ModelApiStyle.class, names = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void malformedFragmentedJsonFailsWithoutCompletingTools(ModelApiStyle style) {
        String sse;
        if (style == ModelApiStyle.OPENAI_RESPONSES) {
            ObjectNode call = call(style, "bad", "nonce");
            sse = event("response.created", ProtocolJson.object().set("response", ProtocolJson.object().put("id", "bad")))
                    + event("response.output_item.added", ProtocolJson.object().put("output_index", 0).set("item", call.deepCopy()))
                    + event("response.function_call_arguments.delta", ProtocolJson.object().put("output_index", 0).put("delta", "{broken"))
                    + event("response.output_item.done", ProtocolJson.object().put("output_index", 0).set("item", call));
        } else {
            var initial = response(style, ProtocolJson.array(), "bad"); initial.putNull("stop_reason");
            var call = call(style, "bad", "nonce"); call.set("input", ProtocolJson.object());
            sse = event("message_start", ProtocolJson.object().set("message", initial))
                    + event("content_block_start", ProtocolJson.object().put("index", 0).set("content_block", call))
                    + event("content_block_delta", ProtocolJson.object().put("index", 0).set("delta", ProtocolJson.object().put("type", "input_json_delta").put("partial_json", "{broken")))
                    + event("content_block_stop", ProtocolJson.object().put("index", 0));
        }
        replies.add(sse(sse)); var result = invoke(model(config(style)), request());
        assertError(result, "MODEL_PROTOCOL_ERROR"); assertEquals(0, result.tools().get());
    }

    private void assertContinuation(ModelApiStyle style, ArrayNode blocks, String nonce, JsonNode request) {
        assertNotNull(request);
        if (style == ModelApiStyle.OPENAI_RESPONSES) {
            List<JsonNode> input = new ArrayList<>(); request.path("input").forEach(input::add);
            for (JsonNode block : blocks) assertTrue(input.contains(block), () -> "Missing original output item: " + block.path("type"));
            assertTrue(input.stream().anyMatch(item -> "function_call_output".equals(item.path("type").asText()) && nonce.equals(item.path("output").asText()) && "call-a".equals(item.path("call_id").asText())));
        } else {
            assertTrue(java.util.stream.StreamSupport.stream(request.path("messages").spliterator(), false).anyMatch(m -> m.path("content").equals(blocks)));
            assertTrue(request.toString().contains("tool_use_id")); assertTrue(request.toString().contains(nonce));
        }
    }
    private ProbeAgent agent(JsonProtocolChatModel model, CustomChatMemoryStore store, Echo echo) {
        return AiServices.builder(ProbeAgent.class).chatModel(model).systemMessage("Use the echo probe when needed.").tools(echo)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(30).chatMemoryStore(store).build()).build();
    }
    interface ProbeAgent { String ask(@MemoryId String memoryId, @dev.langchain4j.service.UserMessage String message); }
    interface StreamingProbeAgent { TokenStream ask(@MemoryId String memoryId, @dev.langchain4j.service.UserMessage String message); }
    static class Echo {
        private final String nonce; final AtomicInteger calls = new AtomicInteger(); Echo(String nonce) { this.nonce = nonce; }
        @Tool("Return a server-generated nonce; no external effects") public String echo(@P("Server-generated nonce") String nonce) { assertEquals(this.nonce, nonce); calls.incrementAndGet(); return nonce; }
    }
    private record Reply(int status, String contentType, String body, CountDownLatch written, CountDownLatch release) {}
    private static Reply json(JsonNode response) { return new Reply(200, "application/json", response.toString(), null, null); }
    private static Reply sse(String response) { return new Reply(200, "text/event-stream", response, null, null); }
    private ResolvedModelConfig config(ModelApiStyle style) {
        return new ResolvedModelConfig("startup/PERSONAL/1/" + style, 1, "connection-" + style, 1,
                new ModelAccessContext(1, WorkspaceTypeEnum.PERSONAL, 1), "FIXTURE", style, "fixture-model", "Fixture",
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/gateway/v1/"), "deployment:qwen", 1,
                style == ModelApiStyle.OPENAI_RESPONSES ? "responses-tools-v1" : "anthropic-tools-v1",
                style == ModelApiStyle.OPENAI_RESPONSES ? "openai-responses-v1" : "anthropic-messages-v1",
                new ResolvedModelConfig.EffectiveOptions(MultiProviderProperties.ThinkingMode.DISABLED, null, null, 4096),
                32768, 24000, true, Set.of("127.0.0.1"));
    }
    private JsonProtocolChatModel model(ResolvedModelConfig config) {
        return model(config,credentials);
    }
    private JsonProtocolChatModel model(ResolvedModelConfig config,CredentialService credentials) {
        HttpClient http = new PolicyHttpClientBuilder(config, () -> new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(), trust))
                .readTimeout(Duration.ofSeconds(5)).build();
        return config.apiStyle() == ModelApiStyle.OPENAI_RESPONSES ? new ResponsesProtocolModel(config, credentials, http)
                : new AnthropicMessagesProtocolModel(config, credentials, http);
    }
    private static ChatRequest request() {
        return ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("probe"))
                .toolSpecifications(ToolSpecification.builder().name("echo").parameters(JsonObjectSchema.builder().addStringProperty("nonce").required("nonce").build()).build()).build();
    }
    private record Result(CompletableFuture<ChatResponse> response, StringBuilder text, AtomicInteger tools, AtomicInteger thoughts) {}
    private static Result invoke(JsonProtocolChatModel model, ChatRequest request) {
        var result = new Result(new CompletableFuture<>(), new StringBuilder(), new AtomicInteger(), new AtomicInteger());
        model.chat(request, new StreamingChatResponseHandler() {
            public void onPartialResponse(String text) { result.text().append(text); }
            public void onPartialThinking(PartialThinking thought) { result.thoughts().incrementAndGet(); }
            public void onCompleteToolCall(CompleteToolCall call) { result.tools().incrementAndGet(); }
            public void onCompleteResponse(ChatResponse response) { result.response().complete(response); }
            public void onError(Throwable error) { result.response().completeExceptionally(error); }
        });
        return result;
    }
    private static void assertError(Result result, String code) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> result.response().get(5, TimeUnit.SECONDS));
        assertInstanceOf(ModelIntegrationException.class, error.getCause()); assertEquals(code, ((ModelIntegrationException) error.getCause()).getReason());
    }
}
