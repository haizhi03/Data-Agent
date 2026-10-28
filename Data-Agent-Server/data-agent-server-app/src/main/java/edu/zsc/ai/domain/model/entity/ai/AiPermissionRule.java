package edu.zsc.ai.domain.model.entity.ai;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.common.enums.permission.CatalogMatchMode;
import edu.zsc.ai.common.enums.permission.PermissionScopeType;
import edu.zsc.ai.common.enums.permission.SchemaMatchMode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiPermissionRule {

    private Long id;

    private PermissionScopeType scopeType;

    private Long userId;

    private WorkspaceTypeEnum workspaceType;

    private Long orgId;

    private Long conversationId;

    private Long connectionId;

    private String catalogName;

    private CatalogMatchMode catalogMatchMode;

    private String schemaName;

    private SchemaMatchMode schemaMatchMode;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
