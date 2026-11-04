package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.ModelAccessContext;
import edu.zsc.ai.common.enums.org.*;
import edu.zsc.ai.domain.service.org.OrgAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ModelManagementAccess {
    private final OrgAccessService organizations;
    public ModelAccessContext current(boolean manage) {
        ModelAccessContext scope = ModelAccessContext.current();
        if (scope.workspaceType() == WorkspaceTypeEnum.ORGANIZATION) {
            try {
                var member = organizations.loadActiveMembership(scope.userId(), scope.ownerId());
                if (manage && member.role() != OrganizationRoleEnum.ADMIN) throw denied();
            } catch (RuntimeException e) { throw denied(); }
        } else if (scope.ownerId() != scope.userId()) throw denied();
        return scope;
    }
    public boolean canManage() {
        try { current(true); return true; } catch (ModelManagementException e) { return false; }
    }
    private static ModelManagementException denied() { return new ModelManagementException("MODEL_MANAGEMENT_FORBIDDEN", HttpStatus.FORBIDDEN); }
}
