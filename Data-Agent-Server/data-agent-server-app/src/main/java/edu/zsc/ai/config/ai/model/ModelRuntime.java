package edu.zsc.ai.config.ai.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import edu.zsc.ai.context.AgentRequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ModelRuntime {
    private final ModelConfigResolver resolver;
    private final ModelClientRegistry registry;
    public boolean enabled() { return resolver.enabled(); }
    public ModelRunSnapshot prepare(String id, String legacyName) { return resolver.resolveRun(id, legacyName); }
    public ModelRunSnapshot prepare(String id,String legacyName,MultiProviderProperties.ThinkingMode thinkingMode) { return resolver.resolveRun(id,legacyName,thinkingMode); }
    public ModelRunSnapshot currentRun() {
        var context = AgentRequestContext.snapshot();
        ModelRunSnapshot run = context == null ? null : context.getModelRunSnapshot();
        if (run == null) throw ModelIntegrationException.unavailable();
        return run;
    }
    public StreamingChatModel streaming(ModelPurpose purpose) { return registry.clients(currentRun(), purpose).streamingChatModel(); }
    public ChatModel chat(ModelPurpose purpose) { return registry.clients(currentRun(), purpose).chatModel(); }
}
