package edu.zsc.ai.agent.subagent;

import edu.zsc.ai.agent.subagent.contract.ExploreObject;
import edu.zsc.ai.common.constant.InvocationContextConstant;
import edu.zsc.ai.common.enums.ai.AgentTypeEnum;
import edu.zsc.ai.config.ai.AiModelCatalog;
import edu.zsc.ai.config.ai.model.ModelPurpose;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.RequestContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

public abstract class AbstractSubAgent<I, O> implements SubAgent<I, O> {

    protected Long resolveConversationId() {
        try {
            Long id = RequestContext.getConversationId();
            return id != null ? id : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    protected String resolveModelName(Logger logger, AiModelCatalog aiModelCatalog) {
        String modelName = AgentRequestContext.getModelName();
        if (modelName != null && !modelName.isBlank()) {
            return modelName;
        }
        logger.warn("AgentRequestContext 中没有 modelName，使用默认模型");
        return aiModelCatalog.defaultModelName();
    }

    protected String resolveModelName(Logger logger, AiModelCatalog catalog, ModelPurpose purpose) {
        var context = AgentRequestContext.snapshot();
        if (context != null && context.getModelRunSnapshot() != null) return context.getModelRunSnapshot().model(purpose).modelConfigId();
        return resolveModelName(logger, catalog);
    }

    protected Map<String, Object> createInvocationContext(AgentTypeEnum agentType) {
        Map<String, Object> invocationContext = new HashMap<>(RequestContext.toMap());
        invocationContext.putAll(AgentRequestContext.toMap());
        invocationContext.put(InvocationContextConstant.AGENT_TYPE, agentType.getCode());
        return invocationContext;
    }

    protected String preview(String value) {
        return SubAgentTextSupport.preview(value);
    }

    protected String summarizeObjects(List<ExploreObject> objects) {
        return SubAgentTextSupport.summarizeObjects(objects);
    }

    protected Throwable rootCause(Throwable throwable) {
        return SubAgentTextSupport.rootCause(throwable);
    }

    protected String rootCauseMessage(Throwable throwable) {
        return SubAgentTextSupport.rootCauseMessage(throwable);
    }

    protected String errorSummary(Throwable throwable, String fallbackMessage) {
        return SubAgentTextSupport.errorSummary(throwable, fallbackMessage);
    }

    protected String errorSummary(Throwable throwable, String fallbackMessage, Long timeoutSeconds) {
        return SubAgentTextSupport.errorSummary(throwable, fallbackMessage, timeoutSeconds);
    }

    protected long resolveTimeoutSeconds(Long requestedTimeoutSeconds, long defaultTimeoutSeconds) {
        return SubAgentTimeoutPolicy.normalizeTimeoutSeconds(requestedTimeoutSeconds, defaultTimeoutSeconds);
    }
}
