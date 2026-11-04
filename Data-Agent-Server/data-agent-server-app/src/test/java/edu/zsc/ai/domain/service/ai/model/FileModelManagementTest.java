package edu.zsc.ai.domain.service.ai.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import edu.zsc.ai.api.controller.ai.*;
import edu.zsc.ai.common.enums.org.*;
import edu.zsc.ai.config.ai.*;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.dto.request.ai.ModelManagementRequests;
import edu.zsc.ai.domain.model.dto.response.ai.ModelManagementResponses;
import edu.zsc.ai.domain.service.org.*;
import java.util.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses only temporary JSON files and synthetic provider clients; no database or real keys. */
@Timeout(20)
class FileModelManagementTest {
    @TempDir Path temporary;
    FileModelStore files;
    MutableClock clock=new MutableClock();
    MultiProviderProperties properties;
    FileModelConfigRepository repository;
    ModelConfigResolver resolver;
    ModelManagementService service;
    ModelDiagnosticService diagnostics;
    CredentialService credentials;
    OrgAccessService organizations;
    ModelClientRegistry registry;

    @BeforeEach void setUp() {
        personal(1);
        properties=new MultiProviderProperties(); properties.getModelRouting().setEnabled(true); properties.getModelRouting().setPersistenceEnabled(true);
        properties.getModelRouting().setStorageDirectory(temporary.toString());
        files=new FileModelStore(properties);repository=new FileModelConfigRepository(files,properties);
        organizations=mock(OrgAccessService.class);
        when(organizations.loadActiveMembership(anyLong(),anyLong())).thenAnswer(a->new OrgMemberContext(a.getArgument(1),1,OrganizationRoleEnum.ADMIN));
        var env=new MockEnvironment(){@Override public Map<String,Object> getSystemEnvironment(){return Map.of("MODEL_CREDENTIAL_KEY_V1",Base64.getEncoder().encodeToString(new byte[32]),"FIXTURE_KEY","deployment-fixture");}};
        var cipher=new ModelCredentialCipher(env,properties); var store=new FileCredentialStore(files,cipher);
        var beans=new DefaultListableBeanFactory(); beans.registerSingleton("credentials",store);
        service=new ModelManagementService(files,new ModelManagementAccess(organizations),properties,cipher,beans.getBeanProvider(FileCredentialStore.class),repository);
        credentials=new CredentialService(env,new QwenProperties(),beans.getBeanProvider(FileCredentialStore.class));
        resolver=new ModelConfigResolver(repository,organizations);
        var factory=mock(ModelClientFactory.class); when(factory.supports(any())).thenReturn(true);
        ChatModel chat=new ChatModel(){@Override public ChatResponse chat(ChatRequest request){
            String nonce=request.messages().stream().filter(m->m instanceof ToolExecutionResultMessage).map(m->((ToolExecutionResultMessage)m).text()).reduce((a,b)->b).orElse(null);
            if(request.toolChoice()==ToolChoice.REQUIRED || request.toolChoice()==ToolChoice.AUTO) {
                String text=((UserMessage)request.messages().get(request.messages().size()-1)).singleText();
                var matcher=Pattern.compile("[0-9a-f]{8}-[0-9a-f-]{27,}").matcher(text); assertTrue(matcher.find());
                return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("probe-call").name("echo_probe").arguments("{\"nonce\":\""+matcher.group()+"\"}").build())).finishReason(dev.langchain4j.model.output.FinishReason.TOOL_EXECUTION).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(nonce==null?"OK":nonce)).finishReason(dev.langchain4j.model.output.FinishReason.STOP).build();
        }};
        StreamingChatModel streaming=new StreamingChatModel(){@Override public void chat(ChatRequest request,StreamingChatResponseHandler handler){handler.onPartialResponse("READY");handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("READY")).finishReason(dev.langchain4j.model.output.FinishReason.STOP).build());}};
        when(factory.create(any())).thenReturn(new ModelClientBundle(chat,streaming));
        registry=new ModelClientRegistry(List.of(factory),resolver,credentials);
        diagnostics=new ModelDiagnosticService(service,new ModelManagementAccess(organizations),registry,repository,clock);
    }
    @AfterEach void cleanup() { diagnostics.close(); RequestContext.clear();AgentRequestContext.clear(); }
    @Test void simpleSetupCreatesAUsableModelCredentialAndAllDefaultsInOneWrite() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ModelManagementController(service,diagnostics,mock(ModelConnectionTestService.class))).setControllerAdvice(new ModelManagementErrorHandler()).build();
        String response=mvc.perform(post("/api/ai/model-setups").contentType("application/json").content("{\"provider\":\"QWEN\",\"apiStyle\":\"OPENAI_CHAT_COMPLETIONS\",\"endpoint\":\"https://dashscope.aliyuncs.com/compatible-mode/v1\",\"compatibilityProfile\":\"qwen-chat-v1\",\"apiKey\":\"setup-fixture-key\",\"modelId\":\"qwen3.8-max\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.model.modelId").value("qwen3.8-max")).andExpect(jsonPath("$.data.model.name").value("qwen3.8-max"))
                .andExpect(jsonPath("$.data.model.available").value(true)).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("setup-fixture-key"));assertFalse(response.contains("encryptedPayload"));
        var model=service.models().get(0);var connection=service.connections().get(0);
        assertTrue(model.code().startsWith("model-"));assertTrue(connection.code().startsWith("connection-"));
        assertEquals("qwen-tools-v1",model.capabilityProfile());assertNull(model.defaultOptions().getMaxOutputTokens());
        assertEquals(new ConfiguredTokenLimits(null,null),model.tokenLimits());
        assertEquals(1_000_000,model.maxContextTokens());
        var run=resolver.resolveRun(null,null);for(var purpose:ModelPurpose.values())assertEquals(model.id(),run.model(purpose).modelConfigId());
        assertEquals(8192,run.model(ModelPurpose.MAIN_CHAT).options().maxOutputTokens());
        assertEquals(2048,run.model(ModelPurpose.COMPRESSION).options().maxOutputTokens());
        assertEquals("setup-fixture-key",credentials.withSecret(run.model(ModelPurpose.MAIN_CHAT),s->s));
        assertEquals(1L,files.read(ModelAccessContext.current(),s->s.revision).longValue());
    }
    @Test void automaticTokenChoicesSurviveFileReloadAndReinferAfterTheModelNameChanges() {
        var first=service.saveSetup(setup(null,null,"automatic-fixture","gpt-4o",false,null));
        assertEquals(128000,first.model().maxContextTokens());assertNull(first.model().tokenLimits().maxContextTokens());
        var rebuilt=new FileModelConfigRepository(new FileModelStore(properties),properties);
        assertEquals(128000,rebuilt.find(first.model().id()).maxContextTokens());
        assertEquals(2048,rebuilt.find(first.model().id(),ModelPurpose.COMPRESSION).options().maxOutputTokens());
        var changed=service.saveSetup(setup(first.connection(),first.model(),null,"local-model-64k",false,null));
        assertEquals(65536,changed.model().maxContextTokens());assertEquals(new ConfiguredTokenLimits(null,null),service.model(changed.model().id()).tokenLimits());
        var disabled=service.disableModel(changed.model().id(),new ModelManagementRequests.Version(changed.model().version()));
        assertEquals(new ConfiguredTokenLimits(null,null),disabled.tokenLimits());
    }
    @Test void selectingAQwenTaskPresetCreatesOnlyTheSelectedVariantAndReusesTheConnectionKey() throws Exception {
        var first=service.saveSetup(setup(null,null,"task-fixture-key","qwen3.8-max",false,null));
        var mvc=MockMvcBuilders.standaloneSetup(new ModelManagementController(service,diagnostics,mock(ModelConnectionTestService.class))).setControllerAdvice(new ModelManagementErrorHandler()).build();
        mvc.perform(put("/api/ai/model-bindings/EXPLORER/preset").contentType("application/json")
                .content(ModelManagementJson.write(new ModelManagementRequests.PresetBinding(1L,first.connection().id(),first.connection().version(),"qwen3.6-flash"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.purpose").value("EXPLORER")).andExpect(jsonPath("$.data.version").value(2));
        var run=resolver.resolveRun(null,null);var explorer=run.model(ModelPurpose.EXPLORER);var variant=service.model(explorer.modelConfigId());
        assertEquals("qwen3.6-flash",explorer.modelId());assertEquals(first.model().id(),run.model(ModelPurpose.MAIN_CHAT).modelConfigId());
        assertTrue(variant.taskPreset());assertFalse(variant.chatVisible());assertEquals(first.connection().id(),variant.connectionId());
        assertEquals(1_000_000,explorer.maxContextTokens());assertEquals(8192,explorer.options().maxOutputTokens());
        assertEquals("task-fixture-key",credentials.withSecret(explorer,key->key));
        assertEquals(2,files.read(ModelAccessContext.current(),state->state.models.size()).intValue());
        assertEquals(1,files.read(ModelAccessContext.current(),state->state.credentials.size()).intValue());
        assertEquals(1,service.connections().size());assertEquals(first.connection().version(),service.connection(first.connection().id()).version());
        assertEquals(first.model().version(),service.model(first.model().id()).version());
    }
    @Test void taskPresetsAreReusedAcrossPurposesAndCanBecomeTheActualMainChatModel() {
        var first=service.saveSetup(setup(null,null,"task-reuse-key","qwen3.8-max",false,null));
        var explorer=selectPreset(ModelPurpose.EXPLORER,first.connection(),"qwen3.6-flash");var oldRun=resolver.resolveRun(null,null);
        assertTrue(resolver.listAvailable().stream().noneMatch(model->model.modelConfigId().equals(explorer.modelConfigId())));
        assertEquals(4,resolver.listAvailable().size()); // Read-only chat choices already include this upstream model.
        var planner=selectPreset(ModelPurpose.PLANNER,first.connection(),"qwen3.6-flash");assertEquals(explorer.modelConfigId(),planner.modelConfigId());
        var main=selectPreset(ModelPurpose.MAIN_CHAT,first.connection(),"qwen3.6-flash");assertEquals(explorer.modelConfigId(),main.modelConfigId());
        assertTrue(service.model(main.modelConfigId()).chatVisible());assertTrue(service.model(main.modelConfigId()).taskPreset());
        assertTrue(resolver.listAvailable().stream().anyMatch(model->model.modelId().equals("qwen3.6-flash")));
        assertEquals(4,resolver.listAvailable().size()); // Promoting a saved task model replaces its virtual choice.
        assertEquals(first.model().id(),selectPreset(ModelPurpose.EXPLORER,first.connection(),"qwen3.8-max").modelConfigId());
        assertEquals("qwen3.6-flash",oldRun.model(ModelPurpose.EXPLORER).modelId());
        assertEquals("qwen3.6-flash",resolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT).modelId());
        assertEquals(2,service.models().size());
    }
    @Test void oneQwenConnectionExposesFourCallableChatChoicesWithoutWritingModelsOrBindings() throws Exception {
        var first=service.saveSetup(setup(null,null,"chat-choice-key","qwen3.8-max",false,null));
        var path=temporary.resolve("PERSONAL/1/models.json");String before=Files.readString(path);
        var bindings=service.bindings();
        var mvc=MockMvcBuilders.standaloneSetup(new AiConfigController(mock(AiModelCatalog.class),resolver)).build();
        String response=mvc.perform(get("/api/ai/models")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].modelId").value("qwen3.8-max")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("chat-choice-key"));assertFalse(response.contains("credentialRef"));
        var choices=resolver.listAvailable();
        assertEquals(QwenModelSpecifications.all().stream().map(QwenModelSpecifications.Specification::modelId).toList(),choices.stream().map(ResolvedModelConfig::modelId).toList());
        assertEquals(4,choices.stream().map(ResolvedModelConfig::modelConfigId).distinct().count());
        var factory=mock(ModelClientFactory.class);when(factory.supports(any())).thenReturn(true);
        when(factory.create(any())).thenAnswer(invocation->{
            ResolvedModelConfig selected=invocation.getArgument(0);
            ChatModel chat=new ChatModel(){@Override public ChatResponse chat(ChatRequest request){
                return ChatResponse.builder().aiMessage(AiMessage.from(selected.modelId())).finishReason(dev.langchain4j.model.output.FinishReason.STOP).build();
            }};
            return new ModelClientBundle(chat,mock(StreamingChatModel.class));
        });
        var clients=new ModelClientRegistry(List.of(factory),resolver,credentials);
        for(var choice:choices) {
            var run=resolver.resolveRun(choice.modelConfigId(),null);var selected=run.model(ModelPurpose.MAIN_CHAT);
            assertEquals(choice.modelId(),selected.modelId());assertEquals(first.connection().id(),selected.connectionId());
            assertEquals(1_000_000,selected.maxContextTokens());assertEquals(8192,selected.options().maxOutputTokens());
            assertEquals("chat-choice-key",credentials.withSecret(selected,key->key));
            for(var purpose:ModelPurpose.values())if(purpose!=ModelPurpose.MAIN_CHAT)assertEquals(first.model().id(),run.model(purpose).modelConfigId());
            assertEquals(choice.modelId(),clients.clients(run,ModelPurpose.MAIN_CHAT).chatModel().chat(ChatRequest.builder().messages(UserMessage.from("hello")).build()).aiMessage().text());
        }
        assertEquals(bindings,service.bindings());assertEquals(1,service.models().size());assertEquals(before,Files.readString(path));
    }
    @Test void taskThinkingSwitchesAreIndependentAndReachTheActualProtocolWithoutEditingTheSharedModel() throws Exception {
        var first=service.saveSetup(setup(null,null,"thinking-fixture","qwen3.8-max",false,null));
        var previous=resolver.resolveRun(null,null);
        var mvc=MockMvcBuilders.standaloneSetup(new ModelManagementController(service,diagnostics,mock(ModelConnectionTestService.class))).setControllerAdvice(new ModelManagementErrorHandler()).build();
        mvc.perform(put("/api/ai/model-bindings/PLANNER").contentType("application/json")
                .content(ModelManagementJson.write(new ModelManagementRequests.Binding(1L,first.model().id(),MultiProviderProperties.ThinkingMode.ENABLED))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.thinkingMode").value("ENABLED"))
                .andExpect(jsonPath("$.data.thinkingModes[0]").value("DISABLED")).andExpect(jsonPath("$.data.thinkingModes[1]").value("ENABLED"));
        service.bind(ModelPurpose.COMPRESSION,new ModelManagementRequests.Binding(1L,first.model().id(),MultiProviderProperties.ThinkingMode.ENABLED));
        var run=resolver.resolveRun(null,null);
        for(var purpose:ModelPurpose.values()) {
            boolean enabled=purpose==ModelPurpose.PLANNER || purpose==ModelPurpose.COMPRESSION;
            assertEquals(enabled?MultiProviderProperties.ThinkingMode.ENABLED:MultiProviderProperties.ThinkingMode.DISABLED,run.model(purpose).options().thinkingMode());
            assertEquals(MultiProviderProperties.ThinkingMode.DISABLED,previous.model(purpose).options().thinkingMode());
            var client=new OpenAiChatClientFactory(credentials).create(run.model(purpose));
            var parameters=(dev.langchain4j.model.openai.OpenAiChatRequestParameters)client.chatModel().defaultRequestParameters();
            assertEquals(enabled,parameters.customParameters().get("enable_thinking"));
        }
        assertEquals(8192,run.model(ModelPurpose.COMPRESSION).options().maxOutputTokens());
        service.bind(ModelPurpose.COMPRESSION,new ModelManagementRequests.Binding(2L,first.model().id(),MultiProviderProperties.ThinkingMode.DISABLED));
        assertEquals(2048,resolver.resolveRun(null,null).model(ModelPurpose.COMPRESSION).options().maxOutputTokens());
        assertEquals(first.model(),service.model(first.model().id()));assertEquals(1,service.models().size());
        assertEquals(first.connection(),service.connection(first.connection().id()));
        resolver.reauthorize(previous.model(ModelPurpose.PLANNER)); // In-flight options remain immutable.
        var before=Files.readString(temporary.resolve("PERSONAL/1/models.json"));
        mvc.perform(put("/api/ai/model-bindings/PLANNER").contentType("application/json")
                .content(ModelManagementJson.write(new ModelManagementRequests.Binding(1L,first.model().id(),MultiProviderProperties.ThinkingMode.DISABLED))))
                .andExpect(status().isConflict());
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
    }
    @Test void conversationThinkingOverridesOnlyTheCurrentMainRequestAndNeverWritesWorkspaceDefaults() throws Exception {
        var first=service.saveSetup(setup(null,null,"conversation-thinking","qwen3.8-max",false,null));
        var path=temporary.resolve("PERSONAL/1/models.json");String before=Files.readString(path);
        var original=resolver.resolveRun(null,null);var defaults=service.bindings();
        for(var choice:resolver.listAvailable()) {
            var thinking=resolver.resolveRun(choice.modelConfigId(),null,MultiProviderProperties.ThinkingMode.ENABLED);
            var ordinary=resolver.resolveRun(choice.modelConfigId(),null,MultiProviderProperties.ThinkingMode.DISABLED);
            assertEquals(MultiProviderProperties.ThinkingMode.ENABLED,thinking.model(ModelPurpose.MAIN_CHAT).options().thinkingMode());
            assertEquals(MultiProviderProperties.ThinkingMode.DISABLED,ordinary.model(ModelPurpose.MAIN_CHAT).options().thinkingMode());
            for(var purpose:ModelPurpose.values())if(purpose!=ModelPurpose.MAIN_CHAT)assertEquals(original.model(purpose),thinking.model(purpose));
            var parameters=(dev.langchain4j.model.openai.OpenAiChatRequestParameters)new OpenAiChatClientFactory(credentials)
                    .create(thinking.model(ModelPurpose.MAIN_CHAT)).chatModel().defaultRequestParameters();
            assertEquals(true,parameters.customParameters().get("enable_thinking"));
            var plain=(dev.langchain4j.model.openai.OpenAiChatRequestParameters)new OpenAiChatClientFactory(credentials)
                    .create(ordinary.model(ModelPurpose.MAIN_CHAT)).chatModel().defaultRequestParameters();
            assertEquals(false,plain.customParameters().get("enable_thinking"));
            resolver.reauthorize(thinking.model(ModelPurpose.MAIN_CHAT));
        }
        assertEquals(MultiProviderProperties.ThinkingMode.DISABLED,original.model(ModelPurpose.MAIN_CHAT).options().thinkingMode());
        assertEquals(defaults,service.bindings());assertEquals(first.model(),service.model(first.model().id()));assertEquals(before,Files.readString(path));
    }
    @Test void conversationThinkingMetadataAndRuntimeEnforceCapabilitiesAndWorkspaceUseRights() {
        var plainConnection=connection("plain-conversation");var plain=model(plainConnection,"plain-conversation");bindAll(plain.id());
        var options=new AiConfigController(mock(AiModelCatalog.class),resolver).listModels().getData();
        assertEquals(List.of(MultiProviderProperties.ThinkingMode.DISABLED),options.get(0).getThinkingModes());assertFalse(options.get(0).isSupportThinking());
        assertEquals("MODEL_CAPABILITY_UNSUPPORTED",assertThrows(ModelIntegrationException.class,()->resolver.resolveRun(plain.id(),null,MultiProviderProperties.ThinkingMode.ENABLED)).getReason());
        organization(8,90);var first=service.saveSetup(setup(null,null,"member-thinking","qwen3.8-max",false,null));
        when(organizations.loadActiveMembership(8,90)).thenReturn(new OrgMemberContext(90,8,OrganizationRoleEnum.COMMON));
        assertFalse(service.status().canManage());
        var choice=resolver.listAvailable().stream().filter(c->c.modelId().equals("qwen-plus")).findFirst().orElseThrow();
        assertEquals(MultiProviderProperties.ThinkingMode.ENABLED,resolver.resolveRun(choice.modelConfigId(),null,MultiProviderProperties.ThinkingMode.ENABLED).model(ModelPurpose.MAIN_CHAT).options().thinkingMode());
        assertThrows(ModelIntegrationException.class,()->resolver.resolveRun(choice.modelConfigId(),null,MultiProviderProperties.ThinkingMode.ADAPTIVE));
        assertEquals(MultiProviderProperties.ThinkingMode.DISABLED,service.bindings().get(0).thinkingMode());
        personal(2);assertThrows(ModelIntegrationException.class,()->resolver.resolveRun(first.model().id(),null,MultiProviderProperties.ThinkingMode.ENABLED));
    }
    @Test void switchingQwenTaskPresetsPreservesThinkingAndTheMainPreferenceAlsoAppliesToChatCandidates() {
        var first=service.saveSetup(setup(null,null,"thinking-presets","qwen3.8-max",false,null));
        service.bindPreset(ModelPurpose.EXPLORER,new ModelManagementRequests.PresetBinding(1L,first.connection().id(),first.connection().version(),"qwen3.6-flash",MultiProviderProperties.ThinkingMode.ENABLED));
        assertEquals(MultiProviderProperties.ThinkingMode.ENABLED,resolver.resolveRun(null,null).model(ModelPurpose.EXPLORER).options().thinkingMode());
        var next=selectPreset(ModelPurpose.EXPLORER,first.connection(),"qwen-plus");
        assertEquals(MultiProviderProperties.ThinkingMode.ENABLED,next.thinkingMode());
        var run=resolver.resolveRun(null,null);
        assertEquals("qwen-plus",run.model(ModelPurpose.EXPLORER).modelId());
        assertEquals(MultiProviderProperties.ThinkingMode.ENABLED,run.model(ModelPurpose.EXPLORER).options().thinkingMode());
        assertEquals(MultiProviderProperties.ThinkingMode.DISABLED,run.model(ModelPurpose.PLANNER).options().thinkingMode());
        service.bind(ModelPurpose.MAIN_CHAT,new ModelManagementRequests.Binding(1L,first.model().id(),MultiProviderProperties.ThinkingMode.ENABLED));
        for(var choice:resolver.listAvailable())assertEquals(MultiProviderProperties.ThinkingMode.ENABLED,resolver.resolveRun(choice.modelConfigId(),null).model(ModelPurpose.MAIN_CHAT).options().thinkingMode());
        assertEquals(MultiProviderProperties.ThinkingMode.DISABLED,service.model(first.model().id()).defaultOptions().getThinkingMode());
    }
    @Test void thinkingOverridesValidateCapabilitiesPermissionsAndKeepOldBindingFilesCompatible() throws Exception {
        var first=service.saveSetup(setup(null,null,"legacy-thinking","qwen3.8-max",false,null));
        var path=temporary.resolve("PERSONAL/1/models.json");
        var tree=ModelManagementJson.read(Files.readString(path),com.fasterxml.jackson.databind.node.ObjectNode.class);
        tree.path("bindings").forEach(binding->((com.fasterxml.jackson.databind.node.ObjectNode)binding).remove("thinkingMode"));
        Files.writeString(path,ModelManagementJson.write(tree));
        assertTrue(service.bindings().stream().allMatch(binding->binding.thinkingMode()==MultiProviderProperties.ThinkingMode.DISABLED));
        assertEquals(MultiProviderProperties.ThinkingMode.DISABLED,resolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT).options().thinkingMode());
        assertEquals(1,ModelManagementJson.read(Files.readString(path),FileModelState.class).formatVersion);
        var connection=connection("plain-thinking");var plain=model(connection,"plain-thinking");
        var before=Files.readString(path);
        assertThrows(ModelManagementException.class,()->service.bind(ModelPurpose.PLANNER,new ModelManagementRequests.Binding(1L,plain.id(),MultiProviderProperties.ThinkingMode.ENABLED)));
        assertThrows(ModelManagementException.class,()->service.bindPreset(ModelPurpose.EXPLORER,new ModelManagementRequests.PresetBinding(1L,first.connection().id(),first.connection().version(),"qwen-plus",MultiProviderProperties.ThinkingMode.ADAPTIVE)));
        assertEquals(before,Files.readString(path));
        personal(2);assertThrows(ModelManagementException.class,()->service.bind(ModelPurpose.PLANNER,new ModelManagementRequests.Binding(0L,first.model().id(),MultiProviderProperties.ThinkingMode.ENABLED)));
        organization(8,90);when(organizations.loadActiveMembership(8,90)).thenReturn(new OrgMemberContext(90,8,OrganizationRoleEnum.COMMON));
        assertEquals("MODEL_MANAGEMENT_FORBIDDEN",assertThrows(ModelManagementException.class,()->service.bind(ModelPurpose.PLANNER,new ModelManagementRequests.Binding(1L,first.model().id(),MultiProviderProperties.ThinkingMode.ENABLED))).getMessage());
    }
    @Test void taskThinkingSupportsRequiredReasoningAndAnthropicAdaptiveOrManualModes() {
        var connection=connection("reasoning-modes");
        var options=new MultiProviderProperties.Options();options.setThinkingMode(MultiProviderProperties.ThinkingMode.ENABLED);
        var reasoning=service.createModel(new ModelManagementRequests.Model(null,"required",connection.id(),"Required","reasoning-model","responses-reasoning-v1",options,32768,24000,true,true));
        bindAll(reasoning.id());
        assertEquals(List.of(MultiProviderProperties.ThinkingMode.ENABLED),service.bindings().get(0).thinkingModes());
        assertThrows(ModelManagementException.class,()->service.bind(ModelPurpose.PLANNER,new ModelManagementRequests.Binding(1L,reasoning.id(),MultiProviderProperties.ThinkingMode.DISABLED)));
        var anthropic=service.createConnection(new ModelManagementRequests.Connection(null,"anthropic-task","Anthropic","ANTHROPIC",ModelApiStyle.ANTHROPIC_MESSAGES,
                "https://api.anthropic.com/v1","anthropic-messages-v1","anthropic-fixture",true));
        for(String profile:List.of("anthropic-adaptive-v1","anthropic-thinking-v1")) {
            var defaults=new MultiProviderProperties.Options();defaults.setTemperature(0.5);
            var model=service.createModel(new ModelManagementRequests.Model(null,profile,anthropic.id(),profile,"claude-test",profile,defaults,32768,24000,true,true));
            var mode=profile.contains("adaptive")?MultiProviderProperties.ThinkingMode.ADAPTIVE:MultiProviderProperties.ThinkingMode.ENABLED;
            long version=service.bindings().stream().filter(b->b.purpose()==ModelPurpose.PLANNER).findFirst().orElseThrow().version();
            service.bind(ModelPurpose.PLANNER,new ModelManagementRequests.Binding(version,model.id(),mode));
            var selected=resolver.resolveRun(null,null).model(ModelPurpose.PLANNER);
            assertEquals(mode,selected.options().thinkingMode());assertNull(selected.options().temperature());
            assertEquals(profile.contains("adaptive")?null:1024,selected.options().thinkingBudget());
            service.bind(ModelPurpose.PLANNER,new ModelManagementRequests.Binding(version+1,model.id(),MultiProviderProperties.ThinkingMode.DISABLED));
            selected=resolver.resolveRun(null,null).model(ModelPurpose.PLANNER);
            assertEquals(MultiProviderProperties.ThinkingMode.DISABLED,selected.options().thinkingMode());assertNull(selected.options().thinkingBudget());assertEquals(0.5,selected.options().temperature());
        }
    }
    @Test void chatChoicesUseEachConnectionsKeyAndRespectWorkspaceAndOrganizationUsePermissions() throws Exception {
        var first=service.saveSetup(setup(null,null,"first-chat-key","qwen3.8-max",false,null));
        var second=service.saveSetup(setup(null,null,"second-chat-key","qwen3.8-max",false,null));
        var choices=resolver.listAvailable();assertEquals(8,choices.size());
        for(var choice:choices) {
            var selected=resolver.resolveRun(choice.modelConfigId(),null).model(ModelPurpose.MAIN_CHAT);
            assertEquals(choice.connectionId().equals(first.connection().id())?"first-chat-key":"second-chat-key",credentials.withSecret(selected,key->key));
        }
        String foreign=choices.stream().filter(c->c.modelId().equals("qwen-plus")).findFirst().orElseThrow().modelConfigId();
        personal(2);assertTrue(resolver.listAvailable().isEmpty());assertThrows(ModelIntegrationException.class,()->resolver.resolveRun(foreign,null));
        organization(8,90);var shared=service.saveSetup(setup(null,null,"shared-chat-key","qwen3.8-max",false,null));
        var path=temporary.resolve("ORGANIZATION/90/models.json");String before=Files.readString(path);
        when(organizations.loadActiveMembership(8,90)).thenReturn(new OrgMemberContext(90,8,OrganizationRoleEnum.COMMON));
        assertEquals(4,resolver.listAvailable().size());assertFalse(service.status().canManage());assertThrows(ModelManagementException.class,service::connections);
        for(var choice:resolver.listAvailable()) {
            var selected=resolver.resolveRun(choice.modelConfigId(),null).model(ModelPurpose.MAIN_CHAT);
            assertEquals(shared.connection().id(),selected.connectionId());assertEquals("shared-chat-key",credentials.withSecret(selected,key->key));
        }
        assertEquals(before,Files.readString(path));assertNotEquals(first.connection().id(),second.connection().id());
        when(organizations.loadActiveMembership(8,90)).thenThrow(new IllegalStateException("membership revoked"));
        assertThrows(ModelIntegrationException.class,resolver::listAvailable);
    }
    @Test void virtualChatChoicesAreRevokedWhenTheSourceIsDisabledOrItsKeyIsReplaced() {
        var first=service.saveSetup(setup(null,null,"old-chat-key","qwen3.8-max",false,null));
        var choice=resolver.listAvailable().stream().filter(c->c.modelId().equals("qwen-plus")).findFirst().orElseThrow();
        var snapshot=resolver.resolveRun(choice.modelConfigId(),null).model(ModelPurpose.MAIN_CHAT);
        var changed=service.credential(first.connection().id(),new ModelManagementRequests.Credential(first.connection().version(),"REPLACE","new-chat-key"));
        assertThrows(ModelIntegrationException.class,()->resolver.reauthorize(snapshot));
        var latest=resolver.resolveRun(choice.modelConfigId(),null).model(ModelPurpose.MAIN_CHAT);
        assertEquals("new-chat-key",credentials.withSecret(latest,key->key));
        service.disableModel(first.model().id(),new ModelManagementRequests.Version(first.model().version()));
        assertTrue(resolver.listAvailable().isEmpty());assertNull(repository.find(choice.modelConfigId()));
        assertThrows(ModelIntegrationException.class,()->resolver.reauthorize(latest));
        assertTrue(service.connection(changed.id()).enabled()); // The disabled source itself gates its virtual choices.
    }
    @Test void chatPresetIdsCannotOverrideAnArbitraryModelOrGrantAHiddenOrNonQwenConnection() {
        var first=service.saveSetup(setup(null,null,"restricted-chat-key","qwen3.8-max",false,null));
        for(String id:List.of("qwen-preset/"+first.model().id()+"/unlisted-model","qwen-preset/"+first.connection().id()+"/qwen-plus",
                "qwen-preset/"+first.model().id()+"/qwen-plus/extra","qwen-preset/../qwen-plus")) {
            assertNull(repository.find(id));assertThrows(ModelIntegrationException.class,()->resolver.resolveRun(id,null));
        }
        var hidden=service.createModel(new ModelManagementRequests.Model(null,"hidden-plus",first.connection().id(),"Hidden plus","qwen-plus","qwen-tools-v1",
                new MultiProviderProperties.Options(),1_000_000,24000,false,true));
        assertEquals(3,resolver.listAvailable().size());
        assertNull(repository.find("qwen-preset/"+first.model().id()+"/qwen-plus"));
        var task=selectPreset(ModelPurpose.EXPLORER,first.connection(),"qwen3.6-flash");
        assertNull(repository.find("qwen-preset/"+task.modelConfigId()+"/qwen-plus"));
        var openai=connection("other-api");var other=model(openai,"other-model");
        assertNull(repository.find("qwen-preset/"+other.id()+"/qwen-plus"));
        service.disableModel(hidden.id(),new ModelManagementRequests.Version(hidden.version()));
        assertTrue(resolver.listAvailable().stream().noneMatch(c->c.modelId().equals("qwen-plus")));
    }
    @Test void staleOrInvalidPresetBindingsCannotLeaveOrphanVariantsOrCrossWorkspaces() throws Exception {
        var first=service.saveSetup(setup(null,null,"task-guard-key","qwen3.8-max",false,null));
        var path=temporary.resolve("PERSONAL/1/models.json");String before=Files.readString(path);
        assertThrows(ModelManagementException.class,()->service.bindPreset(ModelPurpose.EXPLORER,new ModelManagementRequests.PresetBinding(0L,first.connection().id(),1L,"qwen3.6-flash")));
        assertThrows(ModelManagementException.class,()->service.bindPreset(ModelPurpose.EXPLORER,new ModelManagementRequests.PresetBinding(1L,first.connection().id(),2L,"qwen3.6-flash")));
        assertThrows(ModelManagementException.class,()->service.bindPreset(ModelPurpose.EXPLORER,new ModelManagementRequests.PresetBinding(1L,first.connection().id(),1L,"account-arbitrary-model")));
        assertEquals(before,Files.readString(path));assertEquals(1,service.models().size());
        personal(2);assertThrows(ModelManagementException.class,()->service.bindPreset(ModelPurpose.EXPLORER,new ModelManagementRequests.PresetBinding(0L,first.connection().id(),1L,"qwen3.6-flash")));
        personal(1);assertEquals(before,Files.readString(path));
        organization(8,90);when(organizations.loadActiveMembership(8,90)).thenReturn(new OrgMemberContext(90,8,OrganizationRoleEnum.COMMON));
        assertEquals("MODEL_MANAGEMENT_FORBIDDEN",assertThrows(ModelManagementException.class,()->service.bindPreset(ModelPurpose.EXPLORER,new ModelManagementRequests.PresetBinding(0L,first.connection().id(),1L,"qwen3.6-flash"))).getMessage());
        personal(1);assertEquals(before,Files.readString(path));
        var openai=connection("non-qwen");String withOpenai=Files.readString(path);
        assertThrows(ModelManagementException.class,()->service.bindPreset(ModelPurpose.EXPLORER,new ModelManagementRequests.PresetBinding(1L,openai.id(),openai.version(),"qwen3.6-flash")));
        assertEquals(withOpenai,Files.readString(path));
    }
    @Test void replacingTheConfiguredQwenKeyUpdatesAllItsAutomaticTaskModelsWithoutDetaching() {
        var first=service.saveSetup(setup(null,null,"old-task-key","qwen3.8-max",false,null));
        var selected=selectPreset(ModelPurpose.EXPLORER,first.connection(),"qwen3.6-flash");var old=resolver.resolveRun(null,null).model(ModelPurpose.EXPLORER);
        var edited=service.saveSetup(setup(first.connection(),first.model(),"new-task-key","qwen3.8-max",false,null));
        assertEquals(first.connection().id(),edited.connection().id());assertEquals(1,service.connections().size());
        assertEquals(edited.connection().id(),service.model(selected.modelConfigId()).connectionId());
        assertThrows(ModelIntegrationException.class,()->credentials.withSecret(old,key->key));
        assertEquals("new-task-key",credentials.withSecret(resolver.resolveRun(null,null).model(ModelPurpose.EXPLORER),key->key));
        assertEquals(1,files.read(ModelAccessContext.current(),state->state.credentials.size()).intValue());
    }
    @Test void disablingAndReenablingTheOnlyConfiguredConnectionAppliesToItsTaskModels() {
        var first=service.saveSetup(setup(null,null,"task-disable-key","qwen3.8-max",false,null));
        var selected=selectPreset(ModelPurpose.EXPLORER,first.connection(),"qwen3.6-flash");var old=resolver.resolveRun(null,null).model(ModelPurpose.EXPLORER);
        var disabled=service.disableModel(first.model().id(),new ModelManagementRequests.Version(first.model().version()));
        var connection=service.connection(first.connection().id());assertFalse(connection.enabled());assertFalse(service.model(selected.modelConfigId()).available());
        assertThrows(ModelIntegrationException.class,()->resolver.reauthorize(old));
        assertThrows(ModelManagementException.class,()->selectPreset(ModelPurpose.PLANNER,connection,"qwen-plus"));
        var enabled=service.saveSetup(setup(connection,disabled,null,"qwen3.8-max",false,null));
        assertTrue(enabled.connection().enabled());assertTrue(service.model(selected.modelConfigId()).available());
        assertEquals("qwen3.6-flash",resolver.resolveRun(null,null).model(ModelPurpose.EXPLORER).modelId());
        var input=setup(enabled.connection(),enabled.model(),null,"qwen3.8-max",false,null);
        var disabledInForm=service.saveSetup(new ModelManagementRequests.Setup(input.modelConfigId(),input.modelExpectedVersion(),input.connectionId(),input.connectionExpectedVersion(),input.name(),input.provider(),input.apiStyle(),input.endpoint(),input.compatibilityProfile(),null,input.modelId(),input.capabilityProfile(),input.defaultOptions(),input.maxContextTokens(),input.memoryThreshold(),true,false,false,null));
        assertFalse(disabledInForm.connection().enabled());assertFalse(service.model(selected.modelConfigId()).available());
        assertEquals(enabled.connection().version()+1,disabledInForm.connection().version());
    }
    @Test void servesTheSameOfficialSpecificationsUsedByTheRuntime() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ModelManagementController(service,diagnostics,mock(ModelConnectionTestService.class))).setControllerAdvice(new ModelManagementErrorHandler()).build();
        mvc.perform(get("/api/ai/model-specifications")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4)).andExpect(jsonPath("$.data[0].modelId").value("qwen3.8-max"))
                .andExpect(jsonPath("$.data[0].contextTokens").value(1_000_000)).andExpect(jsonPath("$.data[0].maxOutputTokens").value(131072))
                .andExpect(jsonPath("$.data[1].modelId").value("qwen3.6-flash"))
                .andExpect(jsonPath("$.data[1].contextTokens").value(1_000_000))
                .andExpect(jsonPath("$.data[1].maxOutputTokens").value(65536))
                .andExpect(jsonPath("$.data[1].maxThinkingOutputTokens").value(65536));
    }
    @Test void rejectsOfficialModelCeilingViolationsWithoutPartialWrites() throws Exception {
        var first=service.saveSetup(setup(null,null,"official-limits-fixture","qwen3-max",false,null));
        var base=setup(first.connection(),first.model(),null,"qwen3-max",false,null);
        String before=Files.readString(temporary.resolve("PERSONAL/1/models.json"));
        assertEquals("MODEL_TOKEN_LIMITS_INVALID",assertThrows(ModelManagementException.class,()->service.saveSetup(limits(base,null,65536,MultiProviderProperties.ThinkingMode.ENABLED))).getMessage());
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        assertThrows(ModelManagementException.class,()->service.saveSetup(limits(base,262145,null,MultiProviderProperties.ThinkingMode.DISABLED)));
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        var valid=service.saveSetup(limits(base,null,65536,MultiProviderProperties.ThinkingMode.DISABLED));
        assertEquals(262144,valid.model().maxContextTokens());
        assertEquals(65536,resolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT).options().maxOutputTokens());
    }
    @Test void oldAutomaticFallbackUsesUpdatedSpecificationsWithoutRewritingTheFile() throws Exception {
        var first=service.saveSetup(setup(null,null,"old-automatic-fixture","qwen3.8-max",false,null));
        var path=temporary.resolve("PERSONAL/1/models.json");
        var state=ModelManagementJson.read(Files.readString(path),FileModelState.class);var m=state.models.get(first.model().id());
        state.models.put(m.id(),new FileModelState.Model(m.id(),m.code(),m.connectionId(),m.name(),m.modelId(),m.capabilityProfile(),m.options(),32768,24000,m.chatVisible(),m.version(),m.enabled(),m.tokenLimits()));
        Files.writeString(path,ModelManagementJson.write(state));String before=Files.readString(path);
        assertEquals(1_000_000,service.model(m.id()).maxContextTokens());
        assertEquals(1_000_000,new FileModelConfigRepository(new FileModelStore(properties),properties).find(m.id()).maxContextTokens());
        assertEquals(before,Files.readString(path));
    }
    @Test void previouslySavedOverlargeManualLimitsAreBoundedAtRuntimeWithoutCorruptingOldFiles() throws Exception {
        var first=service.saveSetup(setup(null,null,"old-manual-fixture","qwen-plus",false,null));
        var path=temporary.resolve("PERSONAL/1/models.json");
        var state=ModelManagementJson.read(Files.readString(path),FileModelState.class);var m=state.models.get(first.model().id());
        var options=new ResolvedModelConfig.EffectiveOptions(MultiProviderProperties.ThinkingMode.DISABLED,null,null,65536);
        for(var limits:new ConfiguredTokenLimits[]{new ConfiguredTokenLimits(2_000_000,65536),null}) {
            state.models.put(m.id(),new FileModelState.Model(m.id(),m.code(),m.connectionId(),m.name(),m.modelId(),m.capabilityProfile(),options,2_000_000,24000,m.chatVisible(),m.version(),m.enabled(),limits));
            Files.writeString(path,ModelManagementJson.write(state));String before=Files.readString(path);
            var runtime=repository.find(m.id());assertEquals(1_000_000,runtime.maxContextTokens());assertEquals(32768,runtime.options().maxOutputTokens());
            assertEquals(before,Files.readString(path));
        }
        var providerDefault=new ResolvedModelConfig.EffectiveOptions(MultiProviderProperties.ThinkingMode.DISABLED,null,null,null);
        state.models.put(m.id(),new FileModelState.Model(m.id(),m.code(),m.connectionId(),m.name(),m.modelId(),m.capabilityProfile(),providerDefault,32768,24000,m.chatVisible(),m.version(),m.enabled(),null));
        Files.writeString(path,ModelManagementJson.write(state));
        assertNull(repository.find(m.id()).options().maxOutputTokens());
    }
    @Test void manualLimitsApplyToAllPurposesAndCanBeClearedBackToAutomatic() {
        var first=service.saveSetup(limits(setup(null,null,"manual-fixture","test-model",false,null),64000,10000,MultiProviderProperties.ThinkingMode.DISABLED));
        assertEquals(new ConfiguredTokenLimits(64000,10000),first.model().tokenLimits());
        assertEquals(10000,first.model().defaultOptions().getMaxOutputTokens());
        var run=resolver.resolveRun(null,null);
        for(var purpose:ModelPurpose.values())assertEquals(10000,run.model(purpose).options().maxOutputTokens());
        var auto=service.saveSetup(limits(setup(first.connection(),first.model(),null,"test-model",false,null),null,null,MultiProviderProperties.ThinkingMode.DISABLED));
        assertEquals(new ConfiguredTokenLimits(null,null),auto.model().tokenLimits());assertEquals(32768,auto.model().maxContextTokens());
        assertEquals(2048,resolver.resolveRun(null,null).model(ModelPurpose.MEMORY_WRITER).options().maxOutputTokens());
    }
    @Test void outputRangeAndThinkingContextConflictsRejectTheWholeUpdate() throws Exception {
        var first=service.saveSetup(setup(null,null,"range-fixture","test-model",false,null));
        var base=setup(first.connection(),first.model(),null,"test-model",false,null);
        String before=Files.readString(temporary.resolve("PERSONAL/1/models.json"));
        for(int invalid:new int[]{255,1_000_001}) {
            assertEquals("MODEL_TOKEN_LIMITS_INVALID",assertThrows(ModelManagementException.class,()->service.saveSetup(limits(base,2_000_000,invalid,MultiProviderProperties.ThinkingMode.DISABLED))).getMessage());
            assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        }
        assertThrows(ModelManagementException.class,()->service.saveSetup(limits(base,4096,null,MultiProviderProperties.ThinkingMode.ENABLED)));
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        var thinking=service.saveSetup(limits(base,null,256,MultiProviderProperties.ThinkingMode.ENABLED));
        assertEquals(256,thinking.model().defaultOptions().getMaxOutputTokens());
        for(var purpose:ModelPurpose.values())assertEquals(8192,resolver.resolveRun(null,null).model(purpose).options().maxOutputTokens());
    }
    @Test void oldJsonWithoutAutomaticMetadataKeepsItsManualRuntimeLimits() throws Exception {
        var c=connection("legacy-limits");var old=model(c,"legacy-model");bindAll(old.id());
        var path=temporary.resolve("PERSONAL/1/models.json");
        Files.writeString(path,Files.readString(path).replace(",\"tokenLimits\":null","").replace(",\"taskPreset\":false",""));
        var loaded=service.model(old.id());assertNull(loaded.tokenLimits());
        assertFalse(loaded.taskPreset());
        for(var purpose:ModelPurpose.values())assertEquals(4096,resolver.resolveRun(null,null).model(purpose).options().maxOutputTokens());
    }
    @Test void simpleSetupUpdatesConnectionModelAndCredentialTogetherWhilePreservingIdsAndCodes() throws Exception {
        var first=service.saveSetup(setup(null,null,"first-fixture","upstream-first",false,null));
        var old=resolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT);
        var edited=service.saveSetup(setup(first.connection(),first.model(),"replacement-fixture","upstream-second",false,null));
        assertEquals(first.model().id(),edited.model().id());assertEquals(first.model().code(),edited.model().code());
        assertEquals(first.connection().id(),edited.connection().id());assertEquals(first.connection().code(),edited.connection().code());
        assertEquals(2,edited.model().version());assertEquals(2,edited.connection().version());assertEquals(2,edited.connection().credentialVersion());
        assertEquals("upstream-second",resolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT).modelId());
        assertThrows(ModelIntegrationException.class,()->credentials.withSecret(old,s->s));
        assertEquals("replacement-fixture",credentials.withSecret(resolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT),s->s));
        assertEquals(2L,files.read(ModelAccessContext.current(),s->s.revision).longValue());
        assertTrue(edited.bindings().stream().allMatch(b->b.version()==1));
    }
    @Test void invalidOrConflictingSimpleSetupLeavesTheEntireOldDocumentUntouched() throws Exception {
        var first=service.saveSetup(setup(null,null,"first-fixture","valid-model",false,null));
        String before=Files.readString(temporary.resolve("PERSONAL/1/models.json"));
        var invalid=setup(first.connection(),first.model(),"must-not-commit","invalid-options",false,null);
        var badOptions=new MultiProviderProperties.Options();badOptions.setThinkingMode(MultiProviderProperties.ThinkingMode.ADAPTIVE);
        var bad=new ModelManagementRequests.Setup(invalid.modelConfigId(),invalid.modelExpectedVersion(),invalid.connectionId(),invalid.connectionExpectedVersion(),
                invalid.name(),invalid.provider(),invalid.apiStyle(),invalid.endpoint(),invalid.compatibilityProfile(),invalid.apiKey(),invalid.modelId(),null,badOptions,null,null,null,null,false,null);
        assertThrows(ModelManagementException.class,()->service.saveSetup(bad));
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        assertThrows(ModelManagementException.class,()->service.saveSetup(setup(first.connection(),first.model(),"must-not-commit","valid-model",true,zeroBindingVersions())));
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        var edited=service.saveSetup(setup(first.connection(),first.model(),null,"new-model",false,null));
        before=Files.readString(temporary.resolve("PERSONAL/1/models.json"));
        assertThrows(ModelManagementException.class,()->service.saveSetup(setup(first.connection(),first.model(),"must-not-commit","stale-model",false,null)));
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));assertEquals(2,edited.model().version());
    }
    @Test void changingASharedConnectionDetachesOnlyTheEditedModelAndCopiesItsKeyIndependently() {
        var first=service.saveSetup(setup(null,null,"shared-fixture","first-model",false,null));
        var second=service.createModel(new ModelManagementRequests.Model(null,"second-model",first.connection().id(),"Second","second-upstream",first.model().capabilityProfile(),first.model().defaultOptions(),32768,24000,true,true));
        var old=setup(first.connection(),first.model(),null,"edited-model",false,null);
        var changed=new ModelManagementRequests.Setup(old.modelConfigId(),old.modelExpectedVersion(),old.connectionId(),old.connectionExpectedVersion(),old.name(),old.provider(),old.apiStyle(),"https://proxy.example/v1",
                old.compatibilityProfile(),null,old.modelId(),null,null,null,null,null,null,false,null);
        var edited=service.saveSetup(changed);
        assertNotEquals(first.connection().id(),edited.connection().id());assertEquals(first.connection().id(),service.model(second.id()).connectionId());
        assertEquals(1,service.connection(first.connection().id()).version());
        assertEquals("shared-fixture",credentials.withSecret(service.diagnosticModel(second.id()),s->s));
        assertEquals("shared-fixture",credentials.withSecret(service.diagnosticModel(edited.model().id()),s->s));
        service.credential(edited.connection().id(),new ModelManagementRequests.Credential(1L,"REPLACE","detached-fixture"));
        assertEquals("shared-fixture",credentials.withSecret(service.diagnosticModel(second.id()),s->s));
        assertEquals("detached-fixture",credentials.withSecret(service.diagnosticModel(edited.model().id()),s->s));
    }
    @Test void simpleSetupCompletesAnExistingConnectionWithoutReenteringItsKey() {
        var connection=connection("legacy-only");
        var complete=service.saveSetup(setup(connection,null,null,"real-model-id",false,null));
        assertEquals(connection.id(),complete.connection().id());assertEquals("legacy-only",complete.connection().code());
        assertEquals(1,service.connections().size());assertEquals("real-model-id",complete.model().modelId());
        assertEquals("fixture-secret",credentials.withSecret(resolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT),s->s));
    }
    @Test void detachingAnImportedDeploymentCredentialPreservesItsTrustedReference() {
        var first=service.saveSetup(setup(null,null,"initial-fixture","first",false,null));
        files.update(ModelAccessContext.current(),state->{
            var connection=state.connections.get(first.connection().id());String id=connection.credentialId();
            state.credentials.put(id,new FileModelState.Credential(id,"DEPLOYMENT_REF",null,"env:FIXTURE_KEY",null,1));return null;
        });
        var second=service.createModel(new ModelManagementRequests.Model(null,"shared-import",first.connection().id(),"Shared","shared-model",first.model().capabilityProfile(),first.model().defaultOptions(),32768,24000,true,true));
        var old=setup(first.connection(),first.model(),null,"first",false,null);
        var detached=service.saveSetup(new ModelManagementRequests.Setup(old.modelConfigId(),old.modelExpectedVersion(),old.connectionId(),old.connectionExpectedVersion(),old.name(),old.provider(),old.apiStyle(),"https://proxy.example/v1",
                old.compatibilityProfile(),null,old.modelId(),null,null,null,null,null,null,false,null));
        assertNotEquals(first.connection().id(),detached.connection().id());assertEquals("DEPLOYMENT_REF",detached.connection().credentialKind());
        assertEquals("deployment-fixture",credentials.withSecret(service.diagnosticModel(detached.model().id()),s->s));
        service.credential(detached.connection().id(),new ModelManagementRequests.Credential(1L,"REPLACE","independent-fixture"));
        assertEquals("deployment-fixture",credentials.withSecret(service.diagnosticModel(second.id()),s->s));
    }
    @Test void simpleSetupPreservesCustomPurposeBindingsUnlessDefaultIsExplicitlyRequested() {
        var first=service.saveSetup(setup(null,null,"first-fixture","first",false,null));
        var second=service.saveSetup(setup(null,null,"second-fixture","second",false,null));
        service.bind(ModelPurpose.MEMORY_WRITER,new ModelManagementRequests.Binding(1L,second.model().id()));
        first=service.saveSetup(setup(first.connection(),first.model(),null,"first-edited",false,null));
        assertEquals(second.model().id(),service.bindings().stream().filter(b->b.purpose()==ModelPurpose.MEMORY_WRITER).findFirst().orElseThrow().modelConfigId());
        Map<ModelPurpose,Long> versions=new EnumMap<>(ModelPurpose.class);for(var binding:service.bindings())versions.put(binding.purpose(),binding.version());
        second=service.saveSetup(setup(second.connection(),second.model(),null,"second",true,versions));
        String selected=second.model().id();assertTrue(service.bindings().stream().allMatch(b->selected.equals(b.modelConfigId())));
        assertEquals(first.model().id(),service.models().get(0).id());
    }
    @Test void simpleSetupEnforcesWorkspaceOwnershipAndRequiresANewKeyForANewConfiguration() {
        assertEquals("MODEL_TEST_CREDENTIAL_REQUIRED",assertThrows(ModelManagementException.class,()->service.saveSetup(setup(null,null,null,"no-key",false,null))).getMessage());
        assertTrue(service.connections().isEmpty());assertTrue(service.models().isEmpty());
        var first=service.saveSetup(setup(null,null,"first-fixture","first",false,null));personal(2);
        assertThrows(ModelManagementException.class,()->service.saveSetup(setup(first.connection(),first.model(),null,"foreign",false,null)));assertTrue(service.models().isEmpty());
        organization(8,90);when(organizations.loadActiveMembership(8,90)).thenReturn(new OrgMemberContext(90,8,OrganizationRoleEnum.COMMON));
        assertEquals("MODEL_MANAGEMENT_FORBIDDEN",assertThrows(ModelManagementException.class,()->service.saveSetup(setup(null,null,"secret","denied",false,null))).getMessage());
    }
    @Test void storesCiphertextOnlyAndDistinctDeploymentsUseStringIds() throws Exception {
        var c=connection("one"); var a=model(c,"one"); var b=model(connection("two"),"two"); bindAll(a.id());
        assertNotEquals(a.id(),b.id());assertEquals(2,resolver.listAvailable().size());
        assertEquals(a.id(),resolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT).modelConfigId());
        String json=ModelManagementJson.write(service.connections()); assertFalse(json.contains("fixture-secret"));assertFalse(json.contains("encryptedPayload"));assertFalse(json.contains("credentialRef"));
        String payload=Files.readString(temporary.resolve("PERSONAL/1/models.json"));
        assertFalse(payload.contains("fixture-secret")); assertEquals("fixture-secret",credentials.withSecret(resolver.resolveRun(a.id(),null).model(ModelPurpose.MAIN_CHAT),s->s));
        assertEquals("https://example.com/proxy/v1/responses",c.requestUrl());
    }
    @Test void enforcesPersonalOrganizationIsolationAndLiveRoles() {
        var mine=model(connection("mine"),"mine"); personal(2);
        assertEquals(List.of(),resolver.listAvailable());assertEquals("MODEL_CONFIG_NOT_FOUND",assertThrows(ModelManagementException.class,()->service.model(mine.id())).getMessage());
        var request=modelInput("foreign", "1",null,"foreign"); assertThrows(ModelManagementException.class,()->service.createModel(request));
        organization(7,90);var shared=model(connection("org"),"org");bindAll(shared.id());
        when(organizations.loadActiveMembership(8,90)).thenReturn(new OrgMemberContext(90,8,OrganizationRoleEnum.COMMON));organization(8,90);
        assertEquals(1,resolver.listAvailable().size()); assertFalse(service.status().canManage());assertThrows(ModelManagementException.class,service::connections);assertThrows(ModelManagementException.class,()->service.disableModel(shared.id(),new ModelManagementRequests.Version(1L)));
        var run=resolver.resolveRun(shared.id(),null);
        when(organizations.loadActiveMembership(8,90)).thenThrow(new IllegalStateException("removed"));
        assertThrows(ModelIntegrationException.class,()->resolver.reauthorize(run.model(ModelPurpose.MAIN_CHAT)));
    }
    @Test void freshRepositoryAndCredentialReaderRecoverSavedConfiguration() {
        var c=connection("persisted");var m=model(c,"persisted");bindAll(m.id());
        var rebuiltStore=new FileModelStore(properties);var rebuiltRepository=new FileModelConfigRepository(rebuiltStore,properties);
        var rebuiltResolver=new ModelConfigResolver(rebuiltRepository,organizations);var snapshot=rebuiltResolver.resolveRun(null,null).model(ModelPurpose.MAIN_CHAT);
        assertEquals(m.id(),snapshot.modelConfigId());assertEquals(c.id(),snapshot.connectionId());
        var env=new MockEnvironment(){@Override public Map<String,Object> getSystemEnvironment(){return Map.of("MODEL_CREDENTIAL_KEY_V1",Base64.getEncoder().encodeToString(new byte[32]));}};
        var reader=new FileCredentialStore(rebuiltStore,new ModelCredentialCipher(env,properties));
        assertEquals("fixture-secret",reader.withSecret(snapshot,s->s,ignored->{throw new AssertionError("Unexpected deployment reference");}));
    }
    @Test void editsPreserveOldSnapshotButRotationAndDisableRevokeFutureCalls() {
        var c=connection("c");var m=model(c,"m");bindAll(m.id());var old=resolver.resolveRun(m.id(),null).model(ModelPurpose.MAIN_CHAT);
        var edited=service.updateModel(m.id(),modelInput("m",c.id(),1L,"new-upstream"));
        assertEquals(2,edited.version());assertEquals("fixture-model",old.modelId());assertEquals("new-upstream",resolver.resolveRun(m.id(),null).model(ModelPurpose.MAIN_CHAT).modelId());assertDoesNotThrow(()->resolver.reauthorize(old));
        service.updateConnection(c.id(),new ModelManagementRequests.Connection(1L,"c","c","FIXTURE",ModelApiStyle.OPENAI_RESPONSES,"https://new.example.com/gateway/v1","openai-responses-v1",null,true));
        assertEquals("example.com",old.endpoint().getHost());assertEquals("new.example.com",resolver.resolveRun(m.id(),null).model(ModelPurpose.MAIN_CHAT).endpoint().getHost());assertDoesNotThrow(()->resolver.reauthorize(old));
        assertThrows(ModelManagementException.class,()->service.updateModel(m.id(),modelInput("m",c.id(),1L,"stale")));
        var moved=connection("moved");service.updateModel(m.id(),modelInput("m",moved.id(),2L,"moved-model"));assertDoesNotThrow(()->resolver.reauthorize(old));
        service.credential(c.id(),new ModelManagementRequests.Credential(2L,"REPLACE","replacement-fixture"));
        assertEquals(2,service.connection(c.id()).credentialVersion());
        assertThrows(ModelIntegrationException.class,()->resolver.reauthorize(old));assertThrows(ModelIntegrationException.class,()->credentials.withSecret(old,s->true));
        var latest=resolver.resolveRun(m.id(),null).model(ModelPurpose.MAIN_CHAT);assertDoesNotThrow(()->resolver.reauthorize(latest));
        service.disableConnection(moved.id(),new ModelManagementRequests.Version(1L));assertThrows(ModelIntegrationException.class,()->resolver.reauthorize(latest));assertTrue(resolver.listAvailable().stream().noneMatch(x->x.modelConfigId().equals(m.id())));
    }
    @Test void concurrentExpectedVersionsHaveExactlyOneWinner() throws Exception {
        var c=connection("c"); var m=model(c,"m");var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> task=()->{personal(1);start.await();try {service.updateModel(m.id(),modelInput("m",c.id(),1L,"concurrent"));return true;} catch(ModelManagementException e){assertEquals("MODEL_CONFIG_CONFLICT",e.getMessage());return false;}finally{RequestContext.clear();}};
            var one=pool.submit(task);var two=pool.submit(task);start.countDown();assertNotEquals(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS));assertEquals(2,service.model(m.id()).version());
        }finally{pool.shutdownNow();}
    }
    @Test void rejectsCrossWorkspaceReferencesAndConflictingBindings() {
        var c=connection("c");var m=model(c,"m");bindAll(m.id());
        assertThrows(ModelManagementException.class,()->service.bind(ModelPurpose.MAIN_CHAT,new ModelManagementRequests.Binding(0L,m.id())));
        personal(2);assertThrows(ModelManagementException.class,()->service.createModel(modelInput("foreign",c.id(),null,"fixture")));
        assertThrows(ModelManagementException.class,()->service.bind(ModelPurpose.MAIN_CHAT,new ModelManagementRequests.Binding(0L,m.id())));
        assertThrows(ModelManagementException.class,()->service.connection("../../PERSONAL/1/models.json"));
    }
    @Test void startupImportIsScopedOnceAndDoesNotOverwriteEdits() {
        var p=new MultiProviderProperties();p.getModelRouting().setEnabled(true);
        var c=new MultiProviderProperties.Connection();c.setCode("imported");c.setWorkspaceType(WorkspaceTypeEnum.PERSONAL);c.setOwnerId(1L);c.setProvider("FIXTURE");c.setApiStyle(ModelApiStyle.OPENAI_RESPONSES);c.setEndpoint("https://example.com/v1");c.setCredentialRef("env:FIXTURE_KEY");c.setCompatibilityProfile("openai-responses-v1");p.getModelConnections().add(c);
        var m=new MultiProviderProperties.Model();m.setCode("old");m.setName("Old");m.setWorkspaceType(WorkspaceTypeEnum.PERSONAL);m.setOwnerId(1L);m.setConnectionCode("imported");m.setModelId("fixture-model");m.setCapabilityProfile("responses-tools-v1");m.setLegacyNames(List.of("old-name"));p.getModelConfigs().add(m);
        var b=new MultiProviderProperties.Bindings();b.setWorkspaceType(WorkspaceTypeEnum.PERSONAL);b.setOwnerId(1L);for(var purpose:ModelPurpose.values())b.getPurposes().put(purpose,"old");p.getModelBindings().add(b);
        var startup=new StartupModelConfigRepository(p,mock(AiModelCatalog.class),new QwenProperties());startup.initialize();
        var importer=new StartupModelConfigImporter(files,startup);importer.run(null);
        String imported=service.models().get(0).id(),connection=service.connections().get(0).id();
        assertEquals(imported,repository.alias(ModelAccessContext.current(),"old-name"));assertEquals(imported,resolver.resolveRun(null,"old-name").model(ModelPurpose.MAIN_CHAT).modelConfigId());
        service.updateModel(imported,modelInput("old",connection,1L,"edited"));importer.run(null);assertEquals("edited",service.model(imported).modelId());assertEquals(1,service.connections().size());
        properties.getModelRouting().setLegacyAliasesEnabled(false);assertNull(repository.alias(ModelAccessContext.current(),"old-name"));assertNull(repository.find("startup/PERSONAL/1/old"));assertNotNull(repository.find(imported));personal(2);assertTrue(resolver.listAvailable().isEmpty());
    }
    @Test void controllerNeverReturnsSecretsAndConflictsUse409() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ModelManagementController(service,diagnostics,mock(ModelConnectionTestService.class))).setControllerAdvice(new ModelManagementErrorHandler()).build();
        var response=mvc.perform(post("/api/ai/model-connections").contentType("application/json").content(ModelManagementJson.write(connectionInput("c")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("fixture-secret"));assertFalse(response.contains("encrypted_payload"));assertFalse(response.contains("secretRef"));
        String connection=ModelManagementJson.read(response,com.fasterxml.jackson.databind.JsonNode.class).path("data").path("id").asText();
        mvc.perform(post("/api/ai/model-connections/"+connection+"/credential").contentType("application/json").content("{\"expectedVersion\":7,\"action\":\"REPLACE\",\"apiKey\":\"do-not-echo\"}")).andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-echo"))));
        mvc.perform(post("/api/ai/model-connections/"+connection+"/credential").contentType("application/json").content("{\"expectedVersion\":1,\"action\":\"REPLACE\",\"apiKey\":\"******\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai/model-configs").contentType("application/json").content("{\"defaultOptions\":{\"arbitraryProviderOptions\":{\"apiKey\":\"must-not-echo\"}}}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("must-not-echo"))));
        personal(2);mvc.perform(get("/api/ai/model-connections/"+connection)).andExpect(status().isNotFound());
    }
    @Test void diagnosticsKeepOnlyTemporarySafeResultsAndBecomeStaleAfterEdit() throws Exception {
        var c=connection("c");var m=model(c,"m");String before=Files.readString(temporary.resolve("PERSONAL/1/models.json"));var test=diagnostics.start(m.id(),"L4");
        for(int i=0;i<100 && test.status().equals("RUNNING");i++){Thread.sleep(20);test=diagnostics.read(test.id());}
        assertEquals("SUCCEEDED",test.status(),test.result());assertTrue(test.current());assertTrue(test.result().contains("L4"));assertFalse(test.result().contains("fixture-secret"));assertTrue(test.result().contains("COMPLETED_BEFORE_CANCEL"));
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        service.updateModel(m.id(),modelInput("m",c.id(),1L,"edited"));assertFalse(diagnostics.read(test.id()).current());
        personal(2);String id=test.id();assertThrows(ModelManagementException.class,()->diagnostics.read(id));
    }
    @Test void corruptFileReturnsSanitized503ToManagementAndChatCatalogWithoutOverwriting() throws Exception {
        var c=connection("c");model(c,"m");var path=temporary.resolve("PERSONAL/1/models.json");Files.writeString(path,"private-corrupt-file");
        var mvc=MockMvcBuilders.standaloneSetup(new AiConfigController(mock(AiModelCatalog.class),resolver),new ModelManagementController(service,diagnostics,mock(ModelConnectionTestService.class)))
                .setControllerAdvice(new ModelManagementErrorHandler(),new edu.zsc.ai.domain.exception.handler.GlobalExceptionHandler()).build();
        for(String url:List.of("/api/ai/model-connections","/api/ai/models")){
            mvc.perform(get(url)).andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-corrupt-file"))));
        }
        mvc.perform(post("/api/ai/model-connections").contentType("application/json").content(ModelManagementJson.write(connectionInput("next")))).andExpect(status().isServiceUnavailable());
        assertEquals("private-corrupt-file",Files.readString(path));
    }
    @Test void rateLimitsAndShortResultRetentionDoNotDependOnDatabase() throws Exception {
        var c=connection("c");var m=model(c,"m");var first=diagnostics.start(m.id(),"L1");diagnostics.start(m.id(),"L1");diagnostics.start(m.id(),"L1");
        assertEquals("MODEL_TEST_RATE_LIMITED",assertThrows(ModelManagementException.class,()->diagnostics.start(m.id(),"L1")).getMessage());
        clock.now=clock.now.plus(Duration.ofMinutes(31));
        assertEquals("MODEL_TEST_NOT_FOUND",assertThrows(ModelManagementException.class,()->diagnostics.read(first.id())).getMessage());
        assertTrue(diagnostics.latest(m.id()).isEmpty());
    }
    @Test void unlinkingCredentialIsExplicitAndBlocksUseUntilReplacement() {
        var c=connection("c");var m=model(c,"m");bindAll(m.id());var snapshot=resolver.resolveRun(m.id(),null).model(ModelPurpose.MAIN_CHAT);
        assertThrows(ModelManagementException.class,()->service.credential(c.id(),new ModelManagementRequests.Credential(1L,"UNLINK","******")));
        service.credential(c.id(),new ModelManagementRequests.Credential(1L,"UNLINK",null));
        assertFalse(service.connection(c.id()).hasCredential());assertTrue(resolver.listAvailable().isEmpty());assertThrows(ModelIntegrationException.class,()->resolver.reauthorize(snapshot));
        service.credential(c.id(),new ModelManagementRequests.Credential(2L,"REPLACE","new-fixture"));assertEquals(1,resolver.listAvailable().size());
        assertEquals("new-fixture",credentials.withSecret(resolver.resolveRun(m.id(),null).model(ModelPurpose.MAIN_CHAT),s->s));
    }
    @Test void rejectsUnsupportedProfilesLimitsAndInvalidPurposeVisibilityBeforeWriting() {
        var c=connection("c");var options=new MultiProviderProperties.Options();options.setThinkingMode(MultiProviderProperties.ThinkingMode.ENABLED);
        assertThrows(ModelManagementException.class,()->service.createModel(new ModelManagementRequests.Model(null,"bad",c.id(),"Bad","fixture","responses-tools-v1",options,32768,24000,true,true)));
        assertTrue(service.models().isEmpty());
        options.setThinkingMode(MultiProviderProperties.ThinkingMode.DISABLED);
        var hidden=service.createModel(new ModelManagementRequests.Model(null,"hidden",c.id(),"Hidden","fixture","responses-tools-v1",options,32768,24000,false,true));
        assertThrows(ModelManagementException.class,()->service.bind(ModelPurpose.MAIN_CHAT,new ModelManagementRequests.Binding(0L,hidden.id())));
        assertDoesNotThrow(()->service.bind(ModelPurpose.COMPRESSION,new ModelManagementRequests.Binding(0L,hidden.id())));
        assertTrue(resolver.listAvailable().isEmpty());
    }
    @Test void ordinaryMemberSelectorExposesNoManagementFields() throws Exception {
        organization(7,90);var m=model(connection("org"),"org");bindAll(m.id());
        organization(8,90);when(organizations.loadActiveMembership(8,90)).thenReturn(new OrgMemberContext(90,8,OrganizationRoleEnum.COMMON));
        var mvc=MockMvcBuilders.standaloneSetup(new AiConfigController(mock(AiModelCatalog.class),resolver),new ModelManagementController(service,diagnostics,mock(ModelConnectionTestService.class))).setControllerAdvice(new ModelManagementErrorHandler()).build();
        String body=mvc.perform(get("/api/ai/models")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("modelConfigId"));for(var forbidden:List.of("endpoint","credentialRef","fixture-secret","keyVersion","requestUrl"))assertFalse(body.contains(forbidden));
        mvc.perform(get("/api/ai/model-connections")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/model-configs/"+m.id()+"/test").contentType("application/json").content("{\"level\":\"L1\"}")).andExpect(status().isForbidden());
    }
    @Test void springWiringSelectsFileRepositoryAndSavesKeysWithoutEnvironmentOrDataSource() throws Exception {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var environment=new MockEnvironment(){@Override public Map<String,Object> getSystemEnvironment(){return Map.of();}};
            environment.withProperty("ai.model-routing.enabled","true").withProperty("ai.model-routing.persistence-enabled","true").withProperty("ai.model-routing.storage-directory",temporary.toString());
            context.setEnvironment(environment);
            context.registerBean(OrgAccessService.class,()->organizations);context.registerBean(AiModelCatalog.class,()->mock(AiModelCatalog.class));
            context.registerBean(ModelClientFactory.class,()->mock(ModelClientFactory.class));
            context.register(StartupModelConfigRepository.class,FileModelStore.class,FileModelConfigRepository.class,ModelConfigResolver.class,ModelManagementAccess.class,ModelCredentialCipher.class,FileCredentialStore.class,CredentialService.class,ModelManagementService.class,ModelClientRegistry.class,ModelDiagnosticService.class);
            context.refresh();
            assertInstanceOf(FileModelConfigRepository.class,context.getBean(ModelConfigRepository.class));
            assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
            var management=context.getBean(ModelManagementService.class);
            assertTrue(management.status().persistent());assertTrue(management.status().credentialStorageReady());
            var connection=management.createConnection(connectionInput("automatic-key"));
            assertTrue(connection.hasCredential());
            assertTrue(Files.exists(temporary.resolve(".keys/V1.key")));
            assertFalse(Files.readString(temporary.resolve("PERSONAL/1/models.json")).contains("fixture-secret"));
            var model=management.createModel(modelInput("automatic-key",connection.id(),null,"fixture-model"));
            for(var purpose:ModelPurpose.values())management.bind(purpose,new ModelManagementRequests.Binding(0L,model.id()));
            var snapshot=context.getBean(ModelConfigResolver.class).resolveRun(model.id(),null).model(ModelPurpose.MAIN_CHAT);
            var rebuilt=new FileCredentialStore(context.getBean(FileModelStore.class),new ModelCredentialCipher(environment,context.getBean(MultiProviderProperties.class)));
            assertEquals("fixture-secret",rebuilt.withSecret(snapshot,s->s,ignored->{throw new AssertionError("Unexpected deployment reference");}));
        }
    }
    static class MutableClock extends Clock {
        volatile Instant now=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    private ModelManagementResponses.Connection connection(String code){return service.createConnection(connectionInput(code));}
    private ModelManagementRequests.Connection connectionInput(String code){return new ModelManagementRequests.Connection(null,code,code,"FIXTURE",ModelApiStyle.OPENAI_RESPONSES,"https://example.com/proxy/v1","openai-responses-v1","fixture-secret",true);}
    private ModelManagementResponses.Model model(ModelManagementResponses.Connection c,String code){return service.createModel(modelInput(code,c.id(),null,"fixture-model"));}
    private ModelManagementRequests.Model modelInput(String code,String connection,Long version,String modelId){var options=new MultiProviderProperties.Options();return new ModelManagementRequests.Model(version,code,connection,code,modelId,"responses-tools-v1",options,32768,24000,true,true);}
    private void bindAll(String id){for(var p:ModelPurpose.values())service.bind(p,new ModelManagementRequests.Binding(0L,id));}
    private ModelManagementRequests.Setup setup(ModelManagementResponses.Connection connection,ModelManagementResponses.Model model,String key,String upstream,boolean makeDefault,Map<ModelPurpose,Long> bindings){
        return new ModelManagementRequests.Setup(model==null?null:model.id(),model==null?null:model.version(),connection==null?null:connection.id(),connection==null?null:connection.version(),"Simple",connection==null?"QWEN":connection.provider(),
                connection==null?ModelApiStyle.OPENAI_CHAT_COMPLETIONS:connection.apiStyle(),connection==null?"https://dashscope.aliyuncs.com/compatible-mode/v1":connection.endpoint(),
                connection==null?"qwen-chat-v1":connection.compatibilityProfile(),key,upstream,null,null,null,null,null,null,makeDefault,bindings);
    }
    private Map<ModelPurpose,Long> zeroBindingVersions(){Map<ModelPurpose,Long> versions=new EnumMap<>(ModelPurpose.class);for(var purpose:ModelPurpose.values())versions.put(purpose,0L);return versions;}
    private ModelManagementResponses.Binding selectPreset(ModelPurpose purpose,ModelManagementResponses.Connection connection,String modelId) {
        long version=service.bindings().stream().filter(binding->binding.purpose()==purpose).findFirst().orElseThrow().version();
        return service.bindPreset(purpose,new ModelManagementRequests.PresetBinding(version,connection.id(),connection.version(),modelId));
    }
    private ModelManagementRequests.Setup limits(ModelManagementRequests.Setup base,Integer context,Integer output,MultiProviderProperties.ThinkingMode thinking) {
        var options=new MultiProviderProperties.Options();options.setThinkingMode(thinking);options.setMaxOutputTokens(output);
        return new ModelManagementRequests.Setup(base.modelConfigId(),base.modelExpectedVersion(),base.connectionId(),base.connectionExpectedVersion(),base.name(),base.provider(),base.apiStyle(),base.endpoint(),base.compatibilityProfile(),base.apiKey(),base.modelId(),base.capabilityProfile(),options,context,null,base.chatVisible(),base.enabled(),base.makeDefault(),base.bindingVersions());
    }
    private static void personal(long user){RequestContext.set(RequestContextInfo.builder().userId(user).workspaceType(WorkspaceTypeEnum.PERSONAL).build());}
    private static void organization(long user,long org){RequestContext.set(RequestContextInfo.builder().userId(user).workspaceType(WorkspaceTypeEnum.ORGANIZATION).orgId(org).build());}
}
