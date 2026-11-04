package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.api.controller.ai.*;
import edu.zsc.ai.common.enums.org.*;
import edu.zsc.ai.config.ai.QwenProperties;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.dto.request.ai.ModelManagementRequests;
import edu.zsc.ai.domain.model.dto.response.ai.ModelManagementResponses;
import edu.zsc.ai.domain.service.org.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Timeout(20)
class ModelConnectionTestServiceTest {
    @TempDir Path temporary;
    MultiProviderProperties properties;
    FileModelStore files;
    ModelManagementService management;
    CredentialService credentials;
    ModelConnectionProbe probe;
    ModelConnectionTestService tests;
    OrgAccessService organizations;

    @BeforeEach void setup() {
        personal(1);
        properties=new MultiProviderProperties();properties.getModelRouting().setEnabled(true);properties.getModelRouting().setPersistenceEnabled(true);
        properties.getModelRouting().setStorageDirectory(temporary.toString());properties.getModelRouting().setAllowedPrivateHosts(List.of("approved.internal"));
        files=new FileModelStore(properties);
        var environment=new MockEnvironment(){@Override public Map<String,Object> getSystemEnvironment(){return Map.of("MODEL_CREDENTIAL_KEY_V1",Base64.getEncoder().encodeToString(new byte[32]));}};
        var cipher=new ModelCredentialCipher(environment,properties);
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("credentials",new FileCredentialStore(files,cipher));
        credentials=new CredentialService(environment,new QwenProperties(),factory.getBeanProvider(FileCredentialStore.class));
        organizations=mock(OrgAccessService.class);
        when(organizations.loadActiveMembership(anyLong(),anyLong())).thenAnswer(call->new OrgMemberContext(call.getArgument(1),call.getArgument(0),OrganizationRoleEnum.ADMIN));
        management=new ModelManagementService(files,new ModelManagementAccess(organizations),properties,cipher,factory.getBeanProvider(FileCredentialStore.class),new FileModelConfigRepository(files,properties));
        probe=mock(ModelConnectionProbe.class);tests=service(Duration.ofSeconds(2));
    }
    @AfterEach void cleanup() { tests.close();RequestContext.clear();AgentRequestContext.clear(); }
    ModelConnectionTestService service(Duration timeout) { return new ModelConnectionTestService(management,files,properties,credentials,probe,timeout); }
    ModelManagementRequests.ConnectionTest draft() { return input(null,null,"draft-fixture-key","https://example.com/gateway/v1"); }
    ModelManagementRequests.ConnectionTest input(String id,Long version,String secret,String endpoint) {
        return new ModelManagementRequests.ConnectionTest(id,version,"OPENAI",ModelApiStyle.OPENAI_RESPONSES,endpoint,"openai-responses-v1","fixture-model",secret);
    }
    ModelManagementResponses.Connection saved() {
        return management.createConnection(new ModelManagementRequests.Connection(null,"fixture","Fixture","OPENAI",ModelApiStyle.OPENAI_RESPONSES,"https://example.com/v1","openai-responses-v1","saved-fixture-key",true));
    }

    @Test void testsUnsavedConfigurationWithCapturedWorkspaceAndWithoutCreatingFiles() throws Exception {
        doAnswer(call->{
            ResolvedModelConfig config=call.getArgument(0);assertEquals("fixture-model",config.modelId());
            assertEquals("https://example.com/gateway/v1/",config.endpoint().toString());assertEquals("responses-tools-v1",config.capabilityProfile());
            assertEquals(Set.of("approved.internal"),config.allowedPrivateHosts());assertFalse(config.toString().contains("draft-fixture-key"));
            assertEquals(1,ModelAccessContext.current().ownerId());assertNotNull(AgentRequestContext.snapshot().getModelRunSnapshot());
            assertEquals("draft-fixture-key",call.getArgument(1));return null;
        }).when(probe).test(any(),anyString());
        var result=tests.test(draft());assertTrue(result.success());assertEquals("MODEL_TEST_OK",result.code());assertTrue(result.elapsedMs()>=0);
        try(var entries=Files.list(temporary)){assertEquals(0,entries.count());}
        assertFalse(draft().toString().contains("draft-fixture-key"));
        assertNull(AgentRequestContext.snapshot());assertEquals(1,ModelAccessContext.current().ownerId());
    }
    @Test void qwenModelNamesAreForwardedWithoutConsultingALocalModelCatalog() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ModelManagementController(management,mock(ModelDiagnosticService.class),tests))
                .setControllerAdvice(new ModelManagementErrorHandler()).build();
        for(String model:List.of("qwen3.8-max","qwen3.8-max-0902")) {
            var input=new ModelManagementRequests.ConnectionTest(null,null,"QWEN",ModelApiStyle.OPENAI_CHAT_COMPLETIONS,
                    "https://dashscope.aliyuncs.com/compatible-mode/v1","qwen-chat-v1",model,"fixture-key");
            mvc.perform(post("/api/ai/model-connections/test").contentType("application/json").content(ModelManagementJson.write(input)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true));
            verify(probe).test(argThat(config->config.modelId().equals(model) && config.compatibilityProfile().equals("qwen-chat-v1")),eq("fixture-key"));
        }
    }
    @Test void invalidFieldsReturnSpecificSafeMessagesWithoutBlamingTheModelName() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ModelManagementController(management,mock(ModelDiagnosticService.class),tests))
                .setControllerAdvice(new ModelManagementErrorHandler()).build();
        var cases=Map.of(
                "MODEL_TEST_API_INVALID",new ModelManagementRequests.ConnectionTest(null,null,"QWEN",ModelApiStyle.OPENAI_RESPONSES,"https://example.com/v1","qwen-chat-v1","qwen3.8-max","fixture-key"),
                "MODEL_TEST_DASHSCOPE_ENDPOINT_INVALID",new ModelManagementRequests.ConnectionTest(null,null,"QWEN",ModelApiStyle.DASHSCOPE_CHAT,"https://dashscope.aliyuncs.com/compatible-mode/v1","qwen-chat-v1","qwen3.8-max","fixture-key"),
                "MODEL_TEST_ENDPOINT_INVALID",input(null,null,"fixture-key","http://example.com/v1"),
                "MODEL_TEST_KEY_INVALID",input(null,null,"******","https://example.com/v1"),
                "MODEL_TEST_MODEL_ID_INVALID",new ModelManagementRequests.ConnectionTest(null,null,"QWEN",ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"https://example.com/v1","qwen-chat-v1","","fixture-key"),
                "MODEL_TEST_PROVIDER_INVALID",new ModelManagementRequests.ConnectionTest(null,null,"",ModelApiStyle.OPENAI_CHAT_COMPLETIONS,"https://example.com/v1","qwen-chat-v1","qwen3.8-max","fixture-key"),
                "MODEL_TEST_VERSION_INVALID",input(null,1L,"fixture-key","https://example.com/v1"));
        for(var test:cases.entrySet()) {
            mvc.perform(post("/api/ai/model-connections/test").contentType("application/json").content(ModelManagementJson.write(test.getValue())))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(test.getKey()))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fixture-key"))));
        }
        String malformed=ModelManagementJson.write(draft()).replace("\"OPENAI_RESPONSES\"","\"invalid-api-with-secret\"");
        mvc.perform(post("/api/ai/model-connections/test").contentType("application/json").content(malformed))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("MODEL_TEST_API_INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid-api-with-secret"))));
        verifyNoInteractions(probe);
    }
    @Test void savedKeyCanBeUsedWithoutReadbackAndTestingDoesNotChangeConfiguration() throws Exception {
        var connection=saved();String before=Files.readString(temporary.resolve("PERSONAL/1/models.json"));
        var result=tests.test(input(connection.id(),connection.version(),null,"https://proxy.example/v1"));
        assertTrue(result.success());verify(probe).test(argThat(config->config.endpoint().getHost().equals("proxy.example")),eq("saved-fixture-key"));
        assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        assertFalse(ModelManagementJson.write(result).contains("saved-fixture-key"));
    }
    @Test void transientReplacementNeverOverwritesTheSavedKey() throws Exception {
        var connection=saved();String before=Files.readString(temporary.resolve("PERSONAL/1/models.json"));
        assertTrue(tests.test(input(connection.id(),1L,"draft-fixture-key",connection.endpoint())).success());
        verify(probe).test(any(),eq("draft-fixture-key"));assertEquals(before,Files.readString(temporary.resolve("PERSONAL/1/models.json")));
        assertTrue(tests.test(input(connection.id(),1L,null,connection.endpoint())).success());verify(probe).test(any(),eq("saved-fixture-key"));
    }
    @Test void missingKeysInvalidProfilesAndCrossScopeIdsNeverReachTheProvider() {
        assertEquals("MODEL_TEST_CREDENTIAL_REQUIRED",assertThrows(ModelManagementException.class,()->tests.test(input(null,null,null,"https://example.com/v1"))).getMessage());
        for(String endpoint:List.of("http://example.com/v1","https://user:secret@example.com/v1","https://example.com/v1?token=secret")) {
            assertThrows(ModelManagementException.class,()->tests.test(input(null,null,"fixture",endpoint)));
        }
        assertThrows(ModelManagementException.class,()->tests.test(new ModelManagementRequests.ConnectionTest(null,null,"OPENAI",ModelApiStyle.OPENAI_RESPONSES,"https://example.com/v1","qwen-chat-v1","fixture","key")));
        var connection=saved();assertEquals("MODEL_CONFIG_CONFLICT",assertThrows(ModelManagementException.class,()->tests.test(input(connection.id(),2L,null,connection.endpoint()))).getMessage());
        personal(2);assertEquals("MODEL_CONFIG_NOT_FOUND",assertThrows(ModelManagementException.class,()->tests.test(input(connection.id(),1L,null,connection.endpoint()))).getMessage());
        verifyNoInteractions(probe);
    }
    @Test void revocationOrCredentialRotationDuringTheRequestCannotReportSuccess() {
        var connection=saved();
        doAnswer(call->{management.credential(connection.id(),new ModelManagementRequests.Credential(1L,"REPLACE","rotated-fixture"));return null;}).when(probe).test(any(),anyString());
        var result=tests.test(input(connection.id(),1L,null,connection.endpoint()));assertFalse(result.success());assertEquals("MODEL_CONFIG_CONFLICT",result.code());
        reset(probe);organization(1,90);
        doAnswer(call->{when(organizations.loadActiveMembership(1,90)).thenReturn(new OrgMemberContext(90,1,OrganizationRoleEnum.COMMON));return null;}).when(probe).test(any(),anyString());
        result=tests.test(draft());assertFalse(result.success());assertEquals("MODEL_MANAGEMENT_FORBIDDEN",result.code());
    }
    @Test void rateLimitIsPerWorkspace() {
        for(int i=0;i<3;i++)assertTrue(tests.test(draft()).success());
        assertEquals("MODEL_TEST_RATE_LIMITED",assertThrows(ModelManagementException.class,()->tests.test(draft())).getMessage());
        personal(2);assertTrue(tests.test(draft()).success());verify(probe,times(4)).test(any(),anyString());
    }
    @Test void deadlineCancelsTheRunAndDoesNotReturnAStaleSuccess() throws Exception {
        tests.close();tests=service(Duration.ofMillis(200));
        var cancelled=new CountDownLatch(1);var entered=new CountDownLatch(1);
        doAnswer(call->{
            var run=AgentRequestContext.snapshot().getModelRunSnapshot();
            try(var registration=run.track(cancelled::countDown)){entered.countDown();Thread.sleep(10000);}
            return null;
        }).when(probe).test(any(),anyString());
        var result=tests.test(draft());assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(result.success());
        assertEquals("MODEL_UPSTREAM_TIMEOUT",result.code());assertTrue(cancelled.await(1,TimeUnit.SECONDS));
    }
    @Test void boundedWorkersRejectAnotherTestWithoutRunningIt() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var callers=Executors.newFixedThreadPool(2);
        doAnswer(call->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return null;}).when(probe).test(any(),anyString());
        try {
            Callable<ModelManagementResponses.ConnectionTest> request=()->{personal(1);try{return tests.test(draft());}finally{RequestContext.clear();}};
            var first=callers.submit(request);var second=callers.submit(request);assertTrue(entered.await(1,TimeUnit.SECONDS));
            var busy=tests.test(draft());assertFalse(busy.success());assertEquals("MODEL_TEST_BUSY",busy.code());
            release.countDown();assertTrue(first.get(3,TimeUnit.SECONDS).success());assertTrue(second.get(3,TimeUnit.SECONDS).success());
            verify(probe,times(2)).test(any(),anyString());
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void controllerReturnsSafeResultsAndDeniesOrdinaryOrganizationMembers() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ModelManagementController(management,mock(ModelDiagnosticService.class),tests))
                .setControllerAdvice(new ModelManagementErrorHandler()).build();
        doThrow(new ModelIntegrationException("MODEL_AUTH_FAILED",HttpStatus.BAD_GATEWAY)).when(probe).test(any(),anyString());
        String body=mvc.perform(post("/api/ai/model-connections/test").contentType("application/json").content(ModelManagementJson.write(draft())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(false)).andExpect(jsonPath("$.data.code").value("MODEL_AUTH_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("draft-fixture-key"));
        doThrow(new IllegalArgumentException("upstream secret must-not-escape")).when(probe).test(any(),anyString());
        mvc.perform(post("/api/ai/model-connections/test").contentType("application/json").content(ModelManagementJson.write(draft())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("MODEL_TEST_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("must-not-escape"))));
        organization(1,90);when(organizations.loadActiveMembership(1,90)).thenReturn(new OrgMemberContext(90,1,OrganizationRoleEnum.COMMON));
        mvc.perform(post("/api/ai/model-connections/test").contentType("application/json").content(ModelManagementJson.write(draft())))
                .andExpect(status().isForbidden());verify(probe,times(2)).test(any(),anyString());
    }
    private static void personal(long user){RequestContext.set(RequestContextInfo.builder().userId(user).workspaceType(WorkspaceTypeEnum.PERSONAL).build());}
    private static void organization(long user,long org){RequestContext.set(RequestContextInfo.builder().userId(user).workspaceType(WorkspaceTypeEnum.ORGANIZATION).orgId(org).build());}
}
