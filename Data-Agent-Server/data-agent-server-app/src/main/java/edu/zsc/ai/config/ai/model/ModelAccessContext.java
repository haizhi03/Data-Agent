package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.context.RequestContextInfo;

public record ModelAccessContext(long userId, WorkspaceTypeEnum workspaceType, long ownerId) {
    public static ModelAccessContext current() {
        RequestContextInfo context = RequestContext.snapshot();
        if (context == null || context.getUserId() == null || context.getUserId() <= 0) {
            throw ModelIntegrationException.unavailable();
        }
        WorkspaceTypeEnum type = context.getWorkspaceType() == null
                ? (context.getOrgId() == null ? WorkspaceTypeEnum.PERSONAL : WorkspaceTypeEnum.ORGANIZATION)
                : context.getWorkspaceType();
        Long owner = type == WorkspaceTypeEnum.PERSONAL ? context.getUserId() : context.getOrgId();
        if (owner == null || owner <= 0) throw ModelIntegrationException.unavailable();
        return new ModelAccessContext(context.getUserId(), type, owner);
    }
}
