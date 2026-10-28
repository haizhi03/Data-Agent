package edu.zsc.ai.local;

import edu.zsc.ai.context.AgentExecutionContext;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.AgentRequestContextInfo;
import edu.zsc.ai.context.RequestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.assertj.core.api.Assertions.*;

class LocalContextTaskDecoratorTest {
    @AfterEach void clear() {
        RequestContext.clear(); AgentRequestContext.clear(); AgentExecutionContext.clear();
    }

    @Test void reusedWorkerSeparatesSnapshotsAndCleansUpAfterFailure() throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(10);
        executor.setTaskDecorator(new LocalContextTaskDecorator()); executor.initialize();
        var blocker = new CountDownLatch(1);
        try {
            var waiting = executor.submit(() -> { blocker.await(5, TimeUnit.SECONDS); return true; });
            var identity = new LocalRuntimeIdentity();
            var first = identity.newRequestContext(); first.setConversationId(101L);
            RequestContext.set(first);
            var allowed = new ArrayList<>(List.of(7L));
            AgentRequestContext.set(AgentRequestContextInfo.builder().allowedConnectionIds(allowed).build());
            AgentExecutionContext.setTaskId("first");
            var a = executor.submit(() -> List.of(RequestContext.getConversationId().toString(),
                    AgentExecutionContext.getTaskId(), AgentRequestContext.getAllowedConnectionIds().toString()));
            first.setConversationId(202L); allowed.add(8L); AgentExecutionContext.setTaskId("second");
            var b = executor.submit(() -> { assertThat(RequestContext.getConversationId()).isEqualTo(202L);
                assertThat(AgentExecutionContext.getTaskId()).isEqualTo("second"); throw new IllegalStateException("expected"); });
            clear(); blocker.countDown(); waiting.get(5, TimeUnit.SECONDS);
            assertThat(a.get(5, TimeUnit.SECONDS)).containsExactly("101", "first", "[7]");
            assertThatThrownBy(() -> b.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(executor.submit(() -> RequestContext.hasContext() || AgentRequestContext.hasContext()
                    || AgentExecutionContext.getTaskId() != null).get(5, TimeUnit.SECONDS)).isFalse();
            // Inspect the actual reused worker, bypassing the decorator that would mask leaked values.
            assertThat(executor.getThreadPoolExecutor().submit(() -> RequestContext.hasContext()
                    || AgentRequestContext.hasContext() || AgentExecutionContext.getTaskId() != null
                    || AgentExecutionContext.getParentToolCallId() != null).get(5, TimeUnit.SECONDS)).isFalse();
        } finally { blocker.countDown(); executor.shutdown(); }
    }

    @Test void nestedScopeRestoresItsCallerEvenWhenTaskThrows() {
        var identity = new LocalRuntimeIdentity();
        var source = identity.newRequestContext(); source.setConversationId(1L); RequestContext.set(source);
        AgentExecutionContext.setParentToolCallId("captured");
        Runnable task = new LocalContextTaskDecorator().decorate(() -> {
            assertThat(RequestContext.getConversationId()).isEqualTo(1L);
            assertThat(AgentExecutionContext.getParentToolCallId()).isEqualTo("captured");
            RequestContext.get().setConversationId(3L);
            throw new IllegalStateException("expected");
        });
        var caller = identity.newRequestContext(); caller.setConversationId(2L); RequestContext.set(caller);
        AgentExecutionContext.setParentToolCallId("caller");
        assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        assertThat(RequestContext.getConversationId()).isEqualTo(2L);
        assertThat(AgentExecutionContext.getParentToolCallId()).isEqualTo("caller");
    }
}
