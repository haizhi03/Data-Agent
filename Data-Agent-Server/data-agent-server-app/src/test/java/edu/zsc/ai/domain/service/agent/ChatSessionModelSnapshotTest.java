package edu.zsc.ai.domain.service.agent;

import edu.zsc.ai.agent.*;
import edu.zsc.ai.api.model.request.ChatRequest;
import edu.zsc.ai.common.enums.ai.PromptEnum;
import edu.zsc.ai.config.ai.AiModelCatalog;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.service.ai.AiConversationService;
import edu.zsc.ai.domain.service.db.DbConnectionService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatSessionModelSnapshotTest {
    @Test void conversationThinkingIsPassedIntoTheRunAndRetainedForPlanContinuation() {
        var runtime=mock(ModelRuntime.class);when(runtime.enabled()).thenReturn(true);
        var config=mock(ResolvedModelConfig.class);when(config.modelConfigId()).thenReturn("scoped-main");
        var run=new ModelRunSnapshot(Map.of(ModelPurpose.MAIN_CHAT,config),64);
        when(runtime.prepare("scoped-main",null,MultiProviderProperties.ThinkingMode.ENABLED)).thenReturn(run);
        var provider=mock(ReActAgentProvider.class);
        when(provider.getAgent(anyString(),any(),anyString())).thenReturn(new PreparedReActAgent(mock(ReActAgent.class),"prompt",PromptEnum.ZH));
        var databases=mock(DbConnectionService.class);when(databases.getAllConnections()).thenReturn(List.of());
        var sessions=new ChatSessionFactory(provider,mock(AiConversationService.class),databases,mock(AiModelCatalog.class),runtime);
        RequestContext.set(RequestContextInfo.builder().userId(1L).conversationId(9L).build());
        try {
            var request=new ChatRequest();request.setConversationId(9L);request.setMessage("analyze");request.setModelConfigId("scoped-main");
            request.setThinkingMode(MultiProviderProperties.ThinkingMode.ENABLED);
            var original=sessions.create(request);assertSame(run,original.agentRequestContextSnapshot().getModelRunSnapshot());
            request.setThinkingMode(MultiProviderProperties.ThinkingMode.DISABLED);
            var continuation=sessions.createPlanContinuation(original,request);
            assertSame(run,continuation.agentRequestContextSnapshot().getModelRunSnapshot());
            verify(runtime).prepare("scoped-main",null,MultiProviderProperties.ThinkingMode.ENABLED);
            verify(runtime).enabled();
            verifyNoMoreInteractions(runtime);
        } finally {RequestContext.clear();AgentRequestContext.clear();}
    }
    @Test void planContinuationRetainsOriginalPurposeSnapshotInsteadOfResolvingChangedDefaults() {
        var runtime = mock(ModelRuntime.class);
        var config = mock(ResolvedModelConfig.class);
        when(config.modelConfigId()).thenReturn("startup/PERSONAL/1/main");
        var run = new ModelRunSnapshot(Map.of(ModelPurpose.MAIN_CHAT, config), 64);
        when(runtime.enabled()).thenReturn(true);
        when(runtime.prepare(null, null)).thenReturn(run);
        var provider = mock(ReActAgentProvider.class);
        when(provider.getAgent(anyString(), any(), anyString())).thenAnswer(call -> {
            assertSame(run, AgentRequestContext.snapshot().getModelRunSnapshot());
            return new PreparedReActAgent(mock(ReActAgent.class), "prompt", PromptEnum.ZH);
        });
        var databases = mock(DbConnectionService.class);
        when(databases.getAllConnections()).thenReturn(List.of());
        var sessions = new ChatSessionFactory(provider, mock(AiConversationService.class), databases, mock(AiModelCatalog.class), runtime);
        RequestContext.set(RequestContextInfo.builder().userId(1L).conversationId(9L).build());
        try {
            var request = new ChatRequest(); request.setConversationId(9L); request.setMessage("analyze");
            var original = sessions.create(request);
            var continuation = sessions.createPlanContinuation(original, request);
            assertSame(run, continuation.agentRequestContextSnapshot().getModelRunSnapshot());
            assertEquals(original.memoryId(), continuation.memoryId());
            verify(runtime, times(1)).prepare(null, null);
            assertNull(AgentRequestContext.snapshot());
        } finally { RequestContext.clear(); AgentRequestContext.clear(); }
    }
}
