package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.model.ModelAccessContext;
import java.util.UUID;
import java.util.regex.Pattern;

/** Flat, URL-safe IDs include a validated scope; user input never becomes a file path. */
public final class ModelResourceId {
    private static final Pattern ID = Pattern.compile("f([mck])-([PO])-([1-9][0-9]{0,18})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private ModelResourceId() {}
    public static String create(char kind, ModelAccessContext scope) {
        return "f" + kind + "-" + (scope.workspaceType()==WorkspaceTypeEnum.PERSONAL ? "P" : "O") + "-" + scope.ownerId() + "-" + UUID.randomUUID();
    }
    public static ModelAccessContext scope(String id) {
        try {
            var m=ID.matcher(id==null?"":id); if(!m.matches()) throw ModelManagementException.missing();
            long owner=Long.parseLong(m.group(3));
            return new ModelAccessContext(0,m.group(2).equals("P")?WorkspaceTypeEnum.PERSONAL:WorkspaceTypeEnum.ORGANIZATION,owner);
        } catch(RuntimeException e) { throw ModelManagementException.missing(); }
    }
    public static boolean belongs(String id,char kind,ModelAccessContext scope) {
        try { var found=scope(id);return id.charAt(1)==kind && found.workspaceType()==scope.workspaceType() && found.ownerId()==scope.ownerId(); }
        catch(ModelManagementException e) { return false; }
    }
}
