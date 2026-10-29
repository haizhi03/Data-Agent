package edu.zsc.ai.domain.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import edu.zsc.ai.agent.*;
import edu.zsc.ai.agent.memory.MemoryUtil;
import edu.zsc.ai.agent.tool.sql.GetObjectDetailTool;
import edu.zsc.ai.agent.tool.sql.model.ObjectQueryItem;
import edu.zsc.ai.api.model.request.*;
import edu.zsc.ai.common.converter.ai.*;
import edu.zsc.ai.common.enums.ai.PromptEnum;
import edu.zsc.ai.config.ai.AiModelCatalog;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.entity.ai.*;
import edu.zsc.ai.domain.model.entity.db.DbConnection;
import edu.zsc.ai.domain.service.ai.AiConversationService;
import edu.zsc.ai.domain.service.agent.runtimecontext.strategy.ExplicitReferencesStrategy;
import edu.zsc.ai.domain.service.db.*;
import edu.zsc.ai.util.JsonUtil;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatContextChainTest {
    final DbConnectionService dbs = mock(DbConnectionService.class);
    final ConnectionAccessService access = mock(ConnectionAccessService.class);
    final ChatReferenceService references = new ChatReferenceService(access, dbs);
    final AiConversationService conversations = mock(AiConversationService.class);
    final ModelRuntime runtime = mock(ModelRuntime.class);
    final Map<Long, AiConversation> saved = new HashMap<>();
    final List<dev.langchain4j.model.chat.request.ChatRequest> captured = new ArrayList<>();
    final DiscoveryService discovery = mock(DiscoveryService.class);
    final MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(30);
    boolean callTool;

    @BeforeEach void setup() {
        RequestContext.set(RequestContextInfo.builder().userId(7L).build());
        when(dbs.getById(1L)).thenReturn(DbConnection.builder().id(1L).name("development").dbType("mysql").build());
        when(dbs.getById(2L)).thenReturn(DbConnection.builder().id(2L).name("warehouse").dbType("dm").build());
        when(dbs.getAllConnections()).thenReturn(List.of());
        var config = mock(ResolvedModelConfig.class);
        when(config.modelConfigId()).thenReturn("test-model");
        when(runtime.enabled()).thenReturn(true);
        when(runtime.prepare(null, null)).thenAnswer(c -> new ModelRunSnapshot(Map.of(ModelPurpose.MAIN_CHAT, config), 64));
        saved.put(9L, AiConversation.builder().id(9L).userId(7L).defaultConnectionId(1L).defaultCatalogName("sales").build());
        saved.put(10L, AiConversation.builder().id(10L).userId(7L).defaultConnectionId(2L).defaultSchemaName("PUBLIC").build());
        when(conversations.getById(anyLong())).thenAnswer(c -> saved.get(c.getArgument(0)));
        when(conversations.createConversation(anyLong(), anyString())).thenAnswer(c -> {
            var row = AiConversation.builder().id(11L).userId(7L).title(c.getArgument(1)).build(); saved.put(11L, row); return row;
        });
        when(conversations.updateDefaultTarget(anyLong(), anyLong(), any())).thenAnswer(c -> {
            AiConversation row = saved.get(c.getArgument(1)); ChatDefaultTarget target = c.getArgument(2);
            row.setDefaultConnectionId(target.getConnectionId()); row.setDefaultCatalogName(target.getCatalogName());
            row.setDefaultSchemaName(target.getSchemaName()); return row;
        });
        when(discovery.getObjectDetails(anyList())).thenReturn(List.of());
    }
    @AfterEach void clear() { RequestContext.clear(); AgentRequestContext.clear(); }

    ChatSessionFactory factory() {
        StreamingChatModel model = new StreamingChatModel() {
            @Override public void doChat(dev.langchain4j.model.chat.request.ChatRequest request, StreamingChatResponseHandler handler) {
                captured.add(request);
                if (callTool) {
                    callTool = false;
                    var last = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).reduce((x,y) -> y).orElseThrow();
                    var turn = ChatTurnContext.decode(last.singleText());
                    var objects = turn.userMentions().stream().map(m -> new ObjectQueryItem(m.getObjectType(), m.getObjectName(), m.getConnectionId(), m.getCatalogName(), m.getSchemaName())).toList();
                    var call = ToolExecutionRequest.builder().id("details-1").name("getObjectDetail")
                            .arguments(JsonUtil.object2json(Map.of("objects", objects, "description", "Read referenced structures"))).build();
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(call)).build());
                } else handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());
            }
        };
        ReActAgentProvider provider = (name, lang, mode) -> new PreparedReActAgent(
                AiServices.builder(ReActAgent.class).streamingChatModel(model).systemMessage("Database assistant")
                        .chatMemoryProvider(id -> memory).tools(new GetObjectDetailTool(discovery)).build(), "Database assistant", PromptEnum.ZH);
        return new ChatSessionFactory(provider, conversations, dbs, mock(AiModelCatalog.class), runtime, references, new ExplicitReferencesStrategy());
    }
    ChatRequest request(long id, String text) { var request = new ChatRequest(); request.setConversationId(id); request.setMessage(text); return request; }
    ChatUserMention mention(String token, long connection, String catalog, String schema, String object) {
        return new ChatUserMention(token, "TABLE", connection, "untrusted-client-name", catalog, schema, object);
    }
    void run(ChatSession session) throws Exception {
        var done = new CompletableFuture<ChatResponse>();
        session.startChat().onCompleteResponse(done::complete).onError(done::completeExceptionally).start();
        done.get(10, TimeUnit.SECONDS);
    }

    @Test void actualModelRequestAndToolArgumentsRetainBothSameNamedObjects() throws Exception {
        var request = request(9, "compare @orders and @warehouse/ARCHIVE/orders");
        request.setUserMentions(List.of(mention("@orders",1,"sales",null,"orders"), mention("@warehouse/ARCHIVE/orders",2,null,"ARCHIVE","orders")));
        callTool = true;
        var session = factory().create(request);
        run(session);
        assertEquals(2, captured.size());
        var user = (UserMessage) captured.get(0).messages().get(1);
        var actual = ChatTurnContext.decode(user.singleText());
        assertNotNull(actual);
        assertEquals("sales", actual.defaultTarget().getCatalogName());
        assertEquals(2, actual.userMentions().size());
        assertEquals("development", actual.userMentions().get(1).getConnectionName());
        assertTrue(user.singleText().contains("ARCHIVE"));
        assertFalse(user.singleText().contains("CREATE TABLE"));
        verify(discovery).getObjectDetails(List.of(new ObjectQueryItem("TABLE","orders",2L,null,"ARCHIVE"),new ObjectQueryItem("TABLE","orders",1L,"sales",null)));
        verify(access, atLeastOnce()).assertReadable(1L);
        verify(access, atLeastOnce()).assertReadable(2L);
        assertEquals(1L, session.requestContextSnapshot().getConnectionId());
    }

    @Test void schemaAliasAndCanonicalSchemaReachActualModelAndInvocationContext() throws Exception {
        for (String field : List.of("schemaName", "schema")) {
            var request = new ObjectMapper().readValue("{\"conversationId\":10,\"message\":\"explain\",\"connectionId\":2,\""+field+"\":\"ARCHIVE\"}", ChatRequest.class);
            var session = factory().create(request);
            assertEquals("ARCHIVE", session.requestContextSnapshot().getSchema());
            try (var ignored = AgentInvocationContext.from(session.parameters())) { assertEquals("ARCHIVE", RequestContext.getSchema()); }
            run(session);
            var actual = captured.get(captured.size()-1).messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).reduce((a,b)->b).orElseThrow();
            assertEquals("ARCHIVE", ChatTurnContext.decode(actual.singleText()).defaultTarget().getSchemaName());
        }
        var canonical = new ObjectMapper().readValue("{\"schemaName\":\"canonical\",\"schema\":\"legacy\",\"catalogName\":\"sales\",\"catalog\":\"old\"}", ChatRequest.class);
        assertEquals("canonical", canonical.getSchema()); assertEquals("sales",canonical.getCatalog());
    }

    @Test void historySerializationRestoresShortTextAndFullIdentitiesForLaterModelTurns() throws Exception {
        var first = request(10,"compare @orders and @dm/ARCHIVE/orders");
        first.setUserMentions(List.of(mention("@orders",2,null,"PUBLIC","orders"),mention("@dm/ARCHIVE/orders",2,null,"ARCHIVE","orders")));
        var session = factory().create(first); run(session);
        var storedUser = memory.messages().stream().filter(UserMessage.class::isInstance).findFirst().orElseThrow();
        String serialized = ChatMessageSerializer.messageToJson(storedUser);
        var restored = ChatMessageDeserializer.messageFromJson(serialized);
        assertEquals(restored, MemoryUtil.normalizeUserMessage(restored));
        var history = new StoredMessageToResponseConverter().toResponse(StoredChatMessage.builder().id(3L).build(), restored);
        assertEquals(first.getMessage(), history.getContent());
        assertEquals(2,history.getUserMentions().size());
        assertEquals(Set.of("PUBLIC","ARCHIVE"), new HashSet<>(history.getUserMentions().stream().map(ChatUserMention::getSchemaName).toList()));
        assertEquals("PUBLIC",history.getDefaultTarget().getSchemaName());
        // Rebuild memory from serialized storage, rather than relying on transient frontend metadata.
        memory.clear(); memory.add(restored);
        run(factory().create(request(10,"continue comparing these tables")));
        assertTrue(captured.get(captured.size()-1).messages().stream().anyMatch(m -> m instanceof UserMessage u && u.singleText().contains("ARCHIVE")));
        assertEquals("PUBLIC",ConversationConverter.toResponse(saved.get(10L)).getDefaultTarget().getSchemaName());
    }

    @Test void targetsAreIndependentAndQueuedSnapshotsDoNotOverwriteSavedDefaults() {
        var sessions=factory();
        assertEquals(1L,sessions.create(request(9,"a")).requestContextSnapshot().getConnectionId());
        assertEquals(2L,sessions.create(request(10,"b")).requestContextSnapshot().getConnectionId());
        var queued=request(9,"previously submitted");queued.setDefaultTarget(new ChatDefaultTarget(1L,"old_sales",null));
        assertEquals("old_sales",sessions.create(queued).requestContextSnapshot().getCatalog());
        assertEquals("sales",saved.get(9L).getDefaultCatalogName());
        verify(conversations, never()).updateDefaultTarget(anyLong(),eq(9L),any());
        saved.put(12L,AiConversation.builder().id(12L).build());
        assertNull(sessions.create(request(12,"legacy")).requestContextSnapshot().getConnectionId());
    }

    @Test void planContinuationRetainsStructuredTurnContext() {
        var request=request(10,"analyze @orders");
        request.setUserMentions(List.of(mention("@orders",2,null,"PUBLIC","orders")));
        var sessions=factory();
        var original=sessions.create(request);
        var continuation=sessions.createPlanContinuation(original,request);
        var turn=ChatTurnContext.decode(continuation.enrichedMessage());
        assertNotNull(turn);
        assertEquals("PUBLIC",turn.defaultTarget().getSchemaName());
        assertEquals("orders",turn.userMentions().get(0).getObjectName());
        assertTrue(turn.message().contains("structured execution plan"));
    }

    @Test void newChatPersistsChosenTargetAndLeavesPreviousConversationUnchanged() {
        var request=new ChatRequest();request.setMessage("new chat");request.setDefaultTarget(new ChatDefaultTarget(2L,null,"ARCHIVE"));
        var created=factory().create(request);
        assertEquals(11L,created.conversationId());
        assertEquals("ARCHIVE",saved.get(11L).getDefaultSchemaName());
        assertEquals("sales",saved.get(9L).getDefaultCatalogName());
    }

    @Test void explicitDatabaseDoesNotInheritTheDefaultDatabasesSchema() {
        RequestContext.set(RequestContextInfo.builder().userId(7L).connectionId(1L).catalog("sales").schema("public").build());
        var tool = new GetObjectDetailTool(discovery);
        var object = new ObjectQueryItem("TABLE", "orders", 1L, "archive", null);
        tool.getObjectDetail(List.of(object), dev.langchain4j.invocation.InvocationParameters.from(Map.of()));
        verify(discovery).getObjectDetails(List.of(object));
        when(discovery.searchObjects("orders", null, 1L, "archive", null)).thenReturn(
                new edu.zsc.ai.agent.tool.sql.model.ObjectSearchResponse(List.of(), 0, false));
        new edu.zsc.ai.agent.tool.sql.SearchObjectsTool(discovery, dbs, access).searchObjects(
                new edu.zsc.ai.agent.tool.sql.model.ObjectSearchQuery("orders", null, 1L, "archive", null),
                dev.langchain4j.invocation.InvocationParameters.from(Map.of()));
        verify(discovery).searchObjects("orders", null, 1L, "archive", null);
    }

    @Test void schemaOnlyDatabaseReferenceCanDiscoverObjectsWithoutAFabricatedCatalog() {
        RequestContext.set(RequestContextInfo.builder().userId(7L).connectionId(2L).schema("ARCHIVE").build());
        when(discovery.searchObjects("orders", null, 2L, null, "ARCHIVE")).thenReturn(
                new edu.zsc.ai.agent.tool.sql.model.ObjectSearchResponse(List.of(), 0, false));
        var tool = new edu.zsc.ai.agent.tool.sql.SearchObjectsTool(discovery, dbs, access);
        var result = tool.searchObjects(new edu.zsc.ai.agent.tool.sql.model.ObjectSearchQuery("orders", null, 2L, null, "ARCHIVE"),
                dev.langchain4j.invocation.InvocationParameters.from(Map.of()));
        assertTrue(result.isSuccess());
        verify(discovery).searchObjects("orders", null, 2L, null, "ARCHIVE");
        verify(access).assertReadable(2L);
    }

    @Test void rejectsAmbiguousTokensAndMalformedObjectIdentities() {
        var one=mention("@orders",2,null,"PUBLIC","orders");
        var other=mention("@orders",2,null,"ARCHIVE","orders");
        assertThrows(ResponseStatusException.class, () -> references.validateMentions("@orders",List.of(one,other)));
        assertThrows(ResponseStatusException.class, () -> references.validateMentions("@orders",List.of(mention("@orders",2,null,null,"orders"))));
        assertThrows(ResponseStatusException.class, () -> references.validateMentions("@orders",List.of(mention("@orders",1,"sales",null,null))));
    }

    @Test void validatesAccessDropsDeletedReferencesAndNormalizesCapabilities() {
        var deleted=mention("@deleted",2,null,"PUBLIC","orders");
        assertTrue(references.validateMentions("plain text",List.of(deleted)).isEmpty());
        verifyNoInteractions(access);
        assertNull(references.normalizeTarget(new ChatDefaultTarget(1L,"sales","not-applicable")).getSchemaName());
        assertNull(references.normalizeTarget(new ChatDefaultTarget(2L,"pseudo-catalog","PUBLIC")).getCatalogName());
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(access).assertReadable(2L);
        assertThrows(ResponseStatusException.class,()->references.validateMentions("@orders",List.of(mention("@orders",2,null,"PUBLIC","orders"))));
        assertThrows(ResponseStatusException.class,()->references.normalizeTarget(new ChatDefaultTarget(null,"sales",null)));
    }
}
