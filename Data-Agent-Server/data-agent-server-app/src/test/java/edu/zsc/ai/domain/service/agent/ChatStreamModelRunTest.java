package edu.zsc.ai.domain.service.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import edu.zsc.ai.agent.ReActAgent;
import edu.zsc.ai.agent.memory.ChatMemoryCompressor;
import edu.zsc.ai.agent.tool.AgentToolTracker;
import edu.zsc.ai.common.enums.ai.AgentModeEnum;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.event.ChatCompletedEvent;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import java.util.Map;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatStreamModelRunTest {
    private final SseEmitterRegistry registry = new SseEmitterRegistry();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChatStreamBridge bridge = new ChatStreamBridge(registry, events, mock(ChatMemoryCompressor.class));
    private final TokenStream stream = mock(TokenStream.class);
    private final ReActAgent agent = mock(ReActAgent.class);
    private final ModelRunSnapshot run = new ModelRunSnapshot(Map.of(), 64);
    private final InvocationParameters parameters = InvocationParameters.from(Map.of("test", "context"));

    @BeforeEach void setUp() { when(agent.chat(anyString(), anyString(), any())).thenReturn(stream); }
    @AfterEach void clear() { RequestContext.clear(); AgentRequestContext.clear(); }

    @Test void truncatedRunEmitsOneIncompleteTerminalEventInsteadOfSuccess() {
        AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        when(stream.onError(any())).thenAnswer(call -> { error.set(call.getArgument(0)); return stream; });
        doAnswer(call -> {
            error.get().accept(new ModelIntegrationException("MODEL_OUTPUT_TRUNCATED", HttpStatus.BAD_GATEWAY));
            error.get().accept(new ModelIntegrationException("MODEL_PROTOCOL_ERROR", HttpStatus.BAD_GATEWAY));
            return null;
        }).when(stream).start();
        var blocks = bridge.bridge(session(), new AgentToolTracker()).collectList().block();
        assertEquals(2, blocks.size());
        assertEquals("STATUS", blocks.get(0).getType());
        assertTrue(blocks.get(1).isDone());
        assertTrue(blocks.get(1).getData().contains("INCOMPLETE"));
        verifyNoInteractions(events);
        assertTrue(registry.get(9L).isEmpty());
    }

    @Test void completionPublishesOriginalContextOnceAndRestoresThreadContext() {
        AtomicReference<Consumer<ChatResponse>> complete = new AtomicReference<>();
        when(stream.onCompleteResponse(any())).thenAnswer(call -> { complete.set(call.getArgument(0)); return stream; });
        doAnswer(call -> {
            var response = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            complete.get().accept(response); complete.get().accept(response); return null;
        }).when(stream).start();
        var blocks = bridge.bridge(session(), new AgentToolTracker()).collectList().block();
        assertEquals(1, blocks.stream().filter(b -> b.isDone()).count());
        assertTrue(blocks.get(0).getData().contains("SUCCEEDED"));
        var captor = org.mockito.ArgumentCaptor.forClass(ChatCompletedEvent.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertSame(parameters, captor.getValue().getInvocationParameters());
        assertNull(RequestContext.snapshot()); assertNull(AgentRequestContext.snapshot());
    }

    @Test void disconnectCancelsRequestsAndConversationFenceRejectsAnotherActiveStream() {
        AtomicInteger cancelled = new AtomicInteger(); run.track(cancelled::incrementAndGet);
        var flux = bridge.bridge(session(), new AgentToolTracker());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> bridge.bridge(session(), new AgentToolTracker()));
        var subscription = flux.subscribe();
        subscription.dispose();
        assertEquals(1, cancelled.get());
        assertTrue(registry.get(9L).isEmpty());
        assertThrows(ModelIntegrationException.class, run::beforeCall);
    }

    private ChatSession session() {
        return new ChatSession("config", AgentModeEnum.AGENT, agent, "1:9:config", "question", parameters, 9L,
                RequestContextInfo.builder().userId(1L).conversationId(9L).build(),
                AgentRequestContextInfo.builder().modelRunSnapshot(run).build());
    }
}
