package edu.zsc.ai.local;

import edu.zsc.ai.context.AgentExecutionContext;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.AgentRequestContextInfo;
import edu.zsc.ai.context.ModelContextScope;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.context.RequestContextInfo;
import java.util.List;
import org.springframework.core.task.TaskDecorator;

/** Explicit submission snapshot; always restores the worker thread, including on failure. */
final class LocalContextTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
        RequestContextInfo request = RequestContext.snapshot();
        AgentRequestContextInfo source = AgentRequestContext.snapshot();
        AgentRequestContextInfo agent = source == null ? null : source.toBuilder()
                .allowedConnectionIds(source.getAllowedConnectionIds() == null ? null : List.copyOf(source.getAllowedConnectionIds()))
                .readableConnectionIds(source.getReadableConnectionIds() == null ? null : List.copyOf(source.getReadableConnectionIds()))
                .build();
        String parent = AgentExecutionContext.getParentToolCallId();
        String taskId = AgentExecutionContext.getTaskId();
        return () -> {
            String previousParent = AgentExecutionContext.getParentToolCallId();
            String previousTask = AgentExecutionContext.getTaskId();
            try (ModelContextScope ignored = new ModelContextScope(request, agent)) {
                AgentExecutionContext.setParentToolCallId(parent);
                AgentExecutionContext.setTaskId(taskId);
                task.run();
            } finally {
                AgentExecutionContext.setParentToolCallId(previousParent);
                AgentExecutionContext.setTaskId(previousTask);
            }
        };
    }
}
