package edu.zsc.ai.config.ai.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.FinishReason;
import edu.zsc.ai.common.constant.InvocationContextConstant;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.service.org.OrgAccessService;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static edu.zsc.ai.config.ai.model.MultiProviderProperties.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelRoutingTest {
    private MultiProviderProperties properties;
    private StartupModelConfigRepository repository;
    private ModelConfigResolver resolver;
    private OrgAccessService orgAccess;
    private CredentialService credentials;

    @BeforeEach void setUp() {
        properties = new MultiProviderProperties();
        properties.getModelRouting().setEnabled(true);
        properties.setModelConnections(List.of(connection("a", WorkspaceTypeEnum.PERSONAL, 1L), connection("b", WorkspaceTypeEnum.PERSONAL, 1L),
                connection("org", WorkspaceTypeEnum.ORGANIZATION, 7L)));
        properties.setModelConfigs(List.of(model("first", "a", WorkspaceTypeEnum.PERSONAL, 1L), model("second", "b", WorkspaceTypeEnum.PERSONAL, 1L),
                model("org-model", "org", WorkspaceTypeEnum.ORGANIZATION, 7L)));
        properties.setModelBindings(List.of(bindings(WorkspaceTypeEnum.PERSONAL, 1L, "first"), bindings(WorkspaceTypeEnum.ORGANIZATION, 7L, "org-model")));
        AiModelCatalog legacy = new AiModelCatalog(new AiModelProperties());
        legacy.initialize();
        QwenProperties qwen = new QwenProperties();
        qwen.setApiKey("test-secret-never-log");
        credentials = new CredentialService(new MockEnvironment(), qwen);
        repository = new StartupModelConfigRepository(properties, legacy, qwen);
        repository.initialize();
        orgAccess = mock(OrgAccessService.class);
        resolver = new ModelConfigResolver(repository, orgAccess);
        personal(1L);
    }
    @AfterEach void clear() { RequestContext.clear(); AgentRequestContext.clear(); }

    @Test void sameUpstreamNameOnTwoConnectionsHasIndependentClientsAndWorkspaceIsolation() {
        ModelClientFactory factory = factory();
        ModelClientRegistry registry = new ModelClientRegistry(List.of(factory), resolver, credentials);
        var first = resolver.resolveRun("startup/PERSONAL/1/first", null);
        var second = resolver.resolveRun("startup/PERSONAL/1/second", null);
        assertEquals(first.model(ModelPurpose.MAIN_CHAT).modelId(), second.model(ModelPurpose.MAIN_CHAT).modelId());
        assertNotEquals(first.model(ModelPurpose.MAIN_CHAT).connectionId(), second.model(ModelPurpose.MAIN_CHAT).connectionId());
        registry.clients(first, ModelPurpose.MAIN_CHAT);
        registry.clients(first, ModelPurpose.MAIN_CHAT);
        registry.clients(second, ModelPurpose.MAIN_CHAT);
        verify(factory, times(2)).create(any());
        assertEquals(2, resolver.listAvailable().size());
        personal(2L);
        assertTrue(resolver.listAvailable().isEmpty());
        assertThrows(ModelIntegrationException.class, () -> resolver.resolveRun("startup/PERSONAL/1/first", null));
    }

    @Test void explicitInvalidHiddenDisabledOrConflictingSelectionNeverFallsBack() {
        assertThrows(ModelIntegrationException.class, () -> resolver.resolveRun("missing", null));
        assertThrows(ModelIntegrationException.class, () -> resolver.resolveRun(null, "upstream-model"));
        assertThrows(ModelIntegrationException.class, () -> resolver.resolveRun("startup/PERSONAL/1/second", "first-old"));
        properties.getModelConfigs().get(1).setChatVisible(false);
        repository.initialize();
        assertThrows(ModelIntegrationException.class, () -> resolver.resolveRun("startup/PERSONAL/1/second", null));
        properties.getModelConfigs().get(1).setEnabled(false);
        repository.initialize();
        assertThrows(ModelIntegrationException.class, () -> resolver.resolveRun("startup/PERSONAL/1/second", null));
    }

    @Test void bindingAndOptionsAreFrozenWhileNewVersionsRebuildCacheAndRevocationStopsOldHandles() {
        ModelClientFactory factory = factory();
        ModelClientRegistry registry = new ModelClientRegistry(List.of(factory), resolver, credentials);
        var original = resolver.resolveRun(null, null);
        ChatModel old = registry.clients(original, ModelPurpose.MAIN_CHAT).chatModel();
        properties.getModelConfigs().get(0).setVersion(2);
        properties.getModelConfigs().get(0).getDefaultOptions().setTemperature(0.3);
        properties.getModelBindings().get(0).getPurposes().put(ModelPurpose.EXPLORER, "second");
        repository.initialize();
        assertEquals(1, original.model(ModelPurpose.MAIN_CHAT).configVersion());
        assertNull(original.model(ModelPurpose.MAIN_CHAT).options().temperature());
        assertEquals("startup/PERSONAL/1/first", original.model(ModelPurpose.EXPLORER).modelConfigId());
        var next = resolver.resolveRun(null, null);
        assertEquals("startup/PERSONAL/1/second", next.model(ModelPurpose.EXPLORER).modelConfigId());
        registry.clients(next, ModelPurpose.MAIN_CHAT);
        verify(factory, times(2)).create(any());
        assertEquals("ok", old.chat(request()).aiMessage().text());
        properties.getModelConnections().get(0).setCredentialVersion(2);
        repository.initialize();
        assertThrows(ModelIntegrationException.class, () -> old.chat(request()));
    }

    @Test void cachedHandlesRecheckActiveOrganizationMembershipOnEveryCall() {
        RequestContext.set(RequestContextInfo.builder().userId(1L).workspaceType(WorkspaceTypeEnum.ORGANIZATION).orgId(7L).build());
        ModelClientRegistry registry = new ModelClientRegistry(List.of(factory()), resolver, credentials);
        var run = resolver.resolveRun(null, null);
        ChatModel client = registry.clients(run, ModelPurpose.MAIN_CHAT).chatModel();
        client.chat(request());
        doThrow(new IllegalStateException("removed")).when(orgAccess).loadActiveMembership(1L, 7L);
        assertThrows(ModelIntegrationException.class, () -> client.chat(request()));
    }

    @Test void cacheDeduplicatesConcurrentCreationAndBudgetIsSharedAcrossPurposes() throws Exception {
        ModelClientFactory factory = factory();
        properties.getModelRouting().setMaxCallsPerRun(2);
        var run = resolver.resolveRun(null, null);
        ModelClientRegistry registry = new ModelClientRegistry(List.of(factory), resolver, credentials);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int n = 0; n < 12; n++) futures.add(pool.submit(() -> registry.clients(run, ModelPurpose.MAIN_CHAT)));
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        verify(factory, times(1)).create(any());
        registry.clients(run, ModelPurpose.MAIN_CHAT).chatModel().chat(request());
        registry.clients(run, ModelPurpose.COMPRESSION).chatModel().chat(request());
        var error = assertThrows(ModelIntegrationException.class, () -> registry.clients(run, ModelPurpose.MEMORY_WRITER).chatModel().chat(request()));
        assertEquals("AGENT_BUDGET_EXCEEDED", error.getReason());
    }

    @Test void completeToolCallbacksWaitForValidatedFinalResponseAndNeverExecuteOnTruncation() {
        AtomicReference<StreamingChatResponseHandler> upstream = new AtomicReference<>();
        StreamingChatModel raw = mock(StreamingChatModel.class);
        doAnswer(call -> { upstream.set(call.getArgument(1)); return null; }).when(raw).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        ModelClientFactory factory = factory();
        when(factory.create(any())).thenReturn(new ModelClientBundle(mock(ChatModel.class), raw));
        ModelClientRegistry registry = new ModelClientRegistry(List.of(factory), resolver, credentials);
        StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);
        var run = resolver.resolveRun(null, null);
        var client = registry.clients(run, ModelPurpose.MAIN_CHAT).streamingChatModel();
        client.chat(request(), downstream);
        var tool = ToolExecutionRequest.builder().id("call-1").name("echo").arguments("{\"nonce\":\"abc\"}").build();
        upstream.get().onCompleteToolCall(new CompleteToolCall(0, tool));
        verifyNoInteractions(downstream);
        upstream.get().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(tool)).finishReason(FinishReason.LENGTH).build());
        verify(downstream, never()).onCompleteToolCall(any());
        verify(downstream, never()).onCompleteResponse(any());
        verify(downstream).onError(any(ModelIntegrationException.class));
        downstream = mock(StreamingChatResponseHandler.class);
        client.chat(request(), downstream);
        var complete = ChatResponse.builder().aiMessage(AiMessage.from(tool)).finishReason(FinishReason.TOOL_EXECUTION).build();
        upstream.get().onCompleteResponse(complete);
        upstream.get().onCompleteResponse(complete);
        verify(downstream, times(1)).onCompleteToolCall(any());
        verify(downstream, times(1)).onCompleteResponse(complete);
    }

    @Test void callbacksRestoreRunContextAndErrorsDoNotExposeProviderSecrets() {
        var run = resolver.resolveRun(null, null);
        AgentRequestContext.set(AgentRequestContextInfo.builder().modelRunSnapshot(run).build());
        AtomicReference<StreamingChatResponseHandler> upstream = new AtomicReference<>();
        StreamingChatModel raw = mock(StreamingChatModel.class);
        doAnswer(call -> { upstream.set(call.getArgument(1)); return null; }).when(raw).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        ModelClientFactory factory = factory();
        when(factory.create(any())).thenReturn(new ModelClientBundle(mock(ChatModel.class), raw));
        var client = new ModelClientRegistry(List.of(factory), resolver, credentials).clients(run, ModelPurpose.MAIN_CHAT).streamingChatModel();
        var downstream = mock(StreamingChatResponseHandler.class);
        doAnswer(call -> { assertSame(run, AgentRequestContext.snapshot().getModelRunSnapshot()); assertEquals(1L, RequestContext.getUserId()); return null; })
                .when(downstream).onCompleteResponse(any());
        client.chat(request(), downstream);
        RequestContext.clear(); AgentRequestContext.clear();
        upstream.get().onCompleteResponse(response());
        assertNull(RequestContext.snapshot()); assertNull(AgentRequestContext.snapshot());
        verify(downstream).onCompleteResponse(any());
        var errorTarget = mock(StreamingChatResponseHandler.class);
        client.chat(request(), errorTarget);
        upstream.get().onError(new IllegalStateException("Authorization: test-secret-never-log"));
        var captor = org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(errorTarget).onError(captor.capture());
        assertFalse(captor.getValue().toString().contains("test-secret"));
        assertNull(captor.getValue().getCause());
    }

    @Test void contextRoundTripKeepsSameRunAndCancellationStopsAllPurposes() {
        var run = resolver.resolveRun(null, null);
        AgentRequestContext.set(AgentRequestContextInfo.builder().modelRunSnapshot(run).build());
        Map<String, Object> map = new HashMap<>(RequestContext.toMap()); map.putAll(AgentRequestContext.toMap());
        AgentRequestContext.clear();
        try (var ignored = AgentInvocationContext.from(InvocationParameters.from(map))) {
            assertSame(run, AgentRequestContext.snapshot().getModelRunSnapshot());
        }
        AtomicInteger cancelled = new AtomicInteger();
        run.track(cancelled::incrementAndGet);
        run.cancel(); run.cancel();
        assertEquals(1, cancelled.get());
        assertThrows(ModelIntegrationException.class, run::beforeCall);
    }

    @Test void unsupportedProtocolProfileDuplicateAliasAndCrossWorkspaceReferenceAreRejected() {
        properties.getModelConnections().get(0).setApiStyle(ModelApiStyle.OPENAI_RESPONSES);
        assertThrows(IllegalStateException.class, repository::initialize);
        properties.getModelConnections().get(0).setApiStyle(ModelApiStyle.OPENAI_CHAT_COMPLETIONS);
        properties.getModelConfigs().get(1).setLegacyNames(List.of("first-old"));
        assertThrows(IllegalStateException.class, repository::initialize);
        properties.getModelConfigs().get(1).setLegacyNames(List.of());
        properties.getModelConfigs().get(1).setConnectionCode("org");
        assertThrows(IllegalStateException.class, repository::initialize);
    }

    @Test void sdkMessageCodecPreservesThinkingToolIdsArgumentsAndErrorResults() {
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("call-1").name("echo").arguments("{\"nonce\":\"abc\"}").build();
        List<ChatMessage> history = List.of(UserMessage.from("probe"), AiMessage.builder().thinking("public thought").toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.builder().id("call-1").toolName("echo").text("failure").isError(true).build());
        var decoded = ChatMessageDeserializer.messagesFromJson(ChatMessageSerializer.messagesToJson(history));
        assertEquals(history, decoded);
    }

    @Test void endpointPreservesProxyPrefixAndBlocksPrivateOrMetadataTargetsByDefault() throws Exception {
        assertEquals("https://example.com/proxy/v1/", ModelEndpointPolicy.normalize("https://example.com/proxy/v1").toString());
        for (String bad : List.of("http://example.com/v1", "https://user:pass@example.com/v1", "https://example.com/v1?key=x", "https://example.com/%2e%2e/")) {
            assertThrows(IllegalStateException.class, () -> ModelEndpointPolicy.normalize(bad));
        }
        assertThrows(java.net.UnknownHostException.class, () -> ModelEndpointPolicy.resolve("127.0.0.1", Set.of()));
        assertFalse(ModelEndpointPolicy.resolve("127.0.0.1", Set.of("127.0.0.1")).isEmpty());
        assertThrows(java.net.UnknownHostException.class, () -> ModelEndpointPolicy.resolve("169.254.169.254", Set.of("169.254.169.254")));
    }

    @Test void legacyImportIsExplicitlyScopedAndNeverExposesSavedSecrets() {
        properties.setModelConnections(List.of()); properties.setModelConfigs(List.of()); properties.setModelBindings(List.of());
        repository.initialize();
        assertTrue(resolver.listAvailable().isEmpty());
        var workspace = bindings(WorkspaceTypeEnum.PERSONAL, 1L, "unused");
        properties.getModelRouting().setLegacyWorkspaces(List.of(workspace));
        repository.initialize();
        var run = resolver.resolveRun(null, "qwen3.6-max-preview");
        assertEquals("qwen3.6-plus", run.model(ModelPurpose.COMPRESSION).modelId());
        assertFalse(run.model(ModelPurpose.MAIN_CHAT).toString().contains("test-secret"));
        personal(2L);
        assertTrue(resolver.listAvailable().isEmpty());
    }

    @Test void duplicateToolCallsAreScopedByAgentInvocationAndFailedWritesAreNeverReplayed() {
        var run = resolver.resolveRun(null, null);
        var call = ToolExecutionRequest.builder().id("call-1").name("write").arguments("{}").build();
        AtomicInteger executed = new AtomicInteger();
        var result = mock(dev.langchain4j.service.tool.ToolExecutionResult.class);
        java.util.function.Supplier<dev.langchain4j.service.tool.ToolExecutionResult> action = () -> { executed.incrementAndGet(); return result; };
        UUID invocation = UUID.randomUUID();
        assertSame(result, run.executeTool(invocation, call, action));
        assertSame(result, run.executeTool(invocation, call, action));
        assertSame(result, run.executeTool(UUID.randomUUID(), call, action));
        assertEquals(2, executed.get());
        var changed = ToolExecutionRequest.builder().id("call-1").name("write").arguments("{\"x\":1}").build();
        assertThrows(ModelIntegrationException.class, () -> run.executeTool(invocation, changed, action));
        UUID failed = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        java.util.function.Supplier<dev.langchain4j.service.tool.ToolExecutionResult> uncertain = () -> { attempts.incrementAndGet(); throw new IllegalStateException("unknown write outcome"); };
        assertThrows(RuntimeException.class, () -> run.executeTool(failed, call, uncertain));
        assertThrows(RuntimeException.class, () -> run.executeTool(failed, call, uncertain));
        assertEquals(1, attempts.get());
    }

    @Test void factoriesMapThinkingExplicitlyWithoutSendingQwenFieldsToGenericEndpoints() {
        var generic = resolver.resolveRun(null, null).model(ModelPurpose.MAIN_CHAT);
        var genericClient = new OpenAiChatClientFactory(credentials).create(generic);
        var genericOptions = (dev.langchain4j.model.openai.OpenAiChatRequestParameters) genericClient.chatModel().defaultRequestParameters();
        assertFalse(genericOptions.customParameters().containsKey("enable_thinking"));
        properties.getModelConnections().get(0).setCompatibilityProfile("qwen-chat-v1");
        properties.getModelConfigs().get(0).setCapabilityProfile("qwen-tools-v1");
        repository.initialize();
        var qwen = resolver.resolveRun(null, null).model(ModelPurpose.MAIN_CHAT);
        var client = new OpenAiChatClientFactory(credentials).create(qwen);
        var options = (dev.langchain4j.model.openai.OpenAiChatRequestParameters) client.streamingChatModel().defaultRequestParameters();
        assertEquals(false, options.customParameters().get("enable_thinking"));
        properties.getModelConnections().get(0).setApiStyle(ModelApiStyle.DASHSCOPE_CHAT);
        properties.getModelConnections().get(0).setEndpoint("https://dashscope.aliyuncs.com/api/v1");
        repository.initialize();
        var nativeClient = new DashScopeClientFactory(credentials).create(resolver.resolveRun(null, null).model(ModelPurpose.MAIN_CHAT));
        var nativeOptions = (dev.langchain4j.community.model.dashscope.QwenChatRequestParameters) nativeClient.chatModel().defaultRequestParameters();
        assertEquals(false, nativeOptions.enableThinking());
        assertEquals(false, nativeOptions.enableSanitizeMessages());
    }

    @Test void deploymentTemplateBindsToSupportedStartupConfiguration() throws Exception {
        var environment = new MockEnvironment();
        var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var sources = loader.load("model-routing-template", new org.springframework.core.io.ClassPathResource("config-templates/model-routing.yml"));
        sources.forEach(environment.getPropertySources()::addLast);
        var sample = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("ai", org.springframework.boot.context.properties.bind.Bindable.of(MultiProviderProperties.class)).get();
        var catalog = new AiModelCatalog(new AiModelProperties()); catalog.initialize();
        var imported = new StartupModelConfigRepository(sample, catalog, new QwenProperties()); imported.initialize();
        var sampleResolver = new ModelConfigResolver(imported, orgAccess);
        assertEquals(2, sampleResolver.listAvailable().size());
        var run = sampleResolver.resolveRun(null, null);
        assertEquals("startup/PERSONAL/1/analysis-main", run.model(ModelPurpose.MAIN_CHAT).modelConfigId());
        assertEquals("startup/PERSONAL/1/analysis-background", run.model(ModelPurpose.COMPRESSION).modelConfigId());
    }

    private static ModelClientFactory factory() {
        var factory = mock(ModelClientFactory.class);
        when(factory.supports(ModelApiStyle.OPENAI_CHAT_COMPLETIONS)).thenReturn(true);
        when(factory.create(any())).thenAnswer(call -> {
            ChatModel chat = mock(ChatModel.class);
            when(chat.chat(any(ChatRequest.class))).thenReturn(response());
            return new ModelClientBundle(chat, mock(StreamingChatModel.class));
        });
        return factory;
    }
    private static ChatResponse response() { return ChatResponse.builder().aiMessage(AiMessage.from("ok")).finishReason(FinishReason.STOP).build(); }
    private static ChatRequest request() { return ChatRequest.builder().messages(UserMessage.from("probe"))
            .toolSpecifications(dev.langchain4j.agent.tool.ToolSpecification.builder().name("echo").build()).build(); }
    private static void personal(Long user) { RequestContext.set(RequestContextInfo.builder().userId(user).workspaceType(WorkspaceTypeEnum.PERSONAL).build()); }
    private static Connection connection(String code, WorkspaceTypeEnum scope, Long owner) {
        Connection c = new Connection(); c.setCode(code); c.setWorkspaceType(scope); c.setOwnerId(owner);
        c.setProvider("TEST"); c.setApiStyle(ModelApiStyle.OPENAI_CHAT_COMPLETIONS); c.setCredentialRef("deployment:qwen");
        c.setEndpoint("https://example.com/" + code + "/v1"); return c;
    }
    private static Model model(String code, String connection, WorkspaceTypeEnum scope, Long owner) {
        Model m = new Model(); m.setCode(code); m.setConnectionCode(connection); m.setWorkspaceType(scope); m.setOwnerId(owner);
        m.setName("Same display name"); m.setModelId("same-upstream-model"); m.setLegacyNames(List.of(code + "-old")); return m;
    }
    private static Bindings bindings(WorkspaceTypeEnum scope, Long owner, String code) {
        Bindings b = new Bindings(); b.setWorkspaceType(scope); b.setOwnerId(owner);
        for (ModelPurpose p : ModelPurpose.values()) b.getPurposes().put(p, code); return b;
    }
}
