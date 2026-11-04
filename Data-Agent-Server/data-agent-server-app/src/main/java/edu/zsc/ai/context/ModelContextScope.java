package edu.zsc.ai.context;

/** Restores model and workspace context around SDK callbacks, including memory-store callbacks. */
public final class ModelContextScope implements AutoCloseable {
    private final RequestContextInfo previousRequest = RequestContext.snapshot();
    private final AgentRequestContextInfo previousAgent = AgentRequestContext.snapshot();
    public ModelContextScope(RequestContextInfo request, AgentRequestContextInfo agent) {
        if (request == null) RequestContext.clear(); else RequestContext.set(request.toBuilder().build());
        if (agent == null) AgentRequestContext.clear(); else AgentRequestContext.set(agent.toBuilder().build());
    }
    @Override public void close() {
        if (previousRequest == null) RequestContext.clear(); else RequestContext.set(previousRequest);
        if (previousAgent == null) AgentRequestContext.clear(); else AgentRequestContext.set(previousAgent);
    }
}
