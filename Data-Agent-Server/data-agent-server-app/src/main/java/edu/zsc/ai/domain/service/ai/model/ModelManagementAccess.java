package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.ModelAccessContext;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ModelManagementAccess {
    public ModelAccessContext current(boolean manage) {
        ModelAccessContext scope = ModelAccessContext.current();
        if (scope.workspaceType() != WorkspaceTypeEnum.PERSONAL || scope.ownerId() != scope.userId()) throw denied();
        return scope;
    }
    public boolean canManage() {
        try { current(true); return true; } catch (ModelManagementException e) { return false; }
    }
    private static ModelManagementException denied() { return new ModelManagementException("MODEL_MANAGEMENT_FORBIDDEN", HttpStatus.FORBIDDEN); }
}
