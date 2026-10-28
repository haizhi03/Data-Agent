package edu.zsc.ai.aspect;

import cn.dev33.satoken.stp.StpUtil;
import edu.zsc.ai.aspect.annotation.EnableRequestContext;
import edu.zsc.ai.context.AgentExecutionContext;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.context.RequestContextInfo;
import edu.zsc.ai.api.model.request.BaseRequest;
import edu.zsc.ai.api.model.request.ChatRequest;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Request Context Aspect
 * Automatically sets and clears RequestContext for controllers annotated with @EnableRequestContext
 */
@Aspect
@Component
@Slf4j
public class RequestContextAspect {
    
    /**
     * Intercept all methods in classes annotated with @EnableRequestContext
     */
    @Around("@within(enableRequestContext)")
    public Object handleRequestContext(ProceedingJoinPoint joinPoint, EnableRequestContext enableRequestContext) throws Throwable {
        try {
            // Find BaseRequest in method arguments
            for (Object arg : joinPoint.getArgs()) {
                if (!(arg instanceof BaseRequest)) {
                    continue;
                }
                
                BaseRequest request = (BaseRequest) arg;

                RequestContextInfo existing = RequestContext.snapshot();

                Long userId = null;
                try {
                    if (StpUtil.isLogin()) {
                        userId = StpUtil.getLoginIdAsLong();
                    }
                } catch (Exception e) {
                    log.warn("Failed to get userId from Sa-Token: {}", e.getMessage());
                }
                if (userId == null && existing != null) {
                    userId = existing.getUserId();
                }

                RequestContextInfo.RequestContextInfoBuilder builder =
                        existing != null ? existing.toBuilder() : RequestContextInfo.builder();

                RequestContextInfo contextInfo = builder
                        .conversationId(request.getConversationId())
                        .userId(userId)
                        .connectionId(request.getConnectionId())
                        .catalog(request.getCatalog())
                        .schema(request.getSchema())
                        .build();

                contextInfo = applyChatWorkspaceBodyOverride(contextInfo, request, userId);

                RequestContext.set(contextInfo);
                
                log.debug("RequestContext set for method: {}.{}, conversationId: {}, userId: {}",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(),
                    contextInfo.getConversationId(),
                    contextInfo.getUserId());
                
                break;  // Found and set context, no need to check remaining args
            }
            
            // Execute the actual method
            return joinPoint.proceed();
            
        } finally {
            // Always clear context after method execution
            if (RequestContext.hasContext()) {
                log.debug("Clearing RequestContext after method: {}.{}",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName());
            }
            AgentExecutionContext.clear();
            AgentRequestContext.clear();
            RequestContext.clear();
        }
    }

    private RequestContextInfo applyChatWorkspaceBodyOverride(RequestContextInfo contextInfo,
                                                              BaseRequest request,
                                                              Long userId) {
        if (!(request instanceof ChatRequest chat) || userId == null
                || !StringUtils.hasText(chat.getClientWorkspaceType())) {
            return contextInfo;
        }
        if ("PERSONAL".equals(chat.getClientWorkspaceType().trim().toUpperCase())) {
            return contextInfo.toBuilder()
                    .workspaceType(WorkspaceTypeEnum.PERSONAL)
                    .orgId(null)
                    .orgUserRelId(null)
                    .orgRole(null)
                    .build();
        }
        return contextInfo;
    }
}
