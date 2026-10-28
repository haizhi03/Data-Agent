package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.JsonNode;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class LocalRuntimeController {
    private final LocalRuntimeIdentity identity;
    private final LocalConnectionService connections;
    private final LocalAssistantService assistant;
    LocalRuntimeController(LocalRuntimeIdentity identity, LocalConnectionService connections, LocalAssistantService assistant) {
        this.identity = identity; this.connections = connections; this.assistant = assistant;
    }

    @GetMapping("/api/runtime/capabilities")
    ApiResponse<Capabilities> capabilities(@RequestBody(required = false) JsonNode ignored) {
        Feature pending = new Feature("NOT_IMPLEMENTED", "FEATURE_NOT_IMPLEMENTED");
        Feature workbench;
        try { connections.list(); workbench = new Feature("READY", null); }
        catch (LocalRequestException e) { workbench = new Feature("UNAVAILABLE", "LOCAL_STORE_UNAVAILABLE"); }
        return ApiResponse.success(new Capabilities(1, "LOCAL", identity.runtimeInstanceId(), false, false, false,
                Map.of("databaseWorkbench", workbench, "aiAssistant", assistant.capability(), "knowledgeMemory", pending, "skills", pending)));
    }

    @RequestMapping("/api/**")
    void unavailable(@RequestBody(required = false) JsonNode ignored) {
        throw new LocalRequestException(409, "LOCAL_FEATURE_NOT_SUPPORTED", "该功能尚未接入本地模式");
    }

    record Feature(String state, String reasonCode) { }
    record Capabilities(int contractVersion, String mode, UUID runtimeInstanceId, boolean accountLoginEnabled,
                        boolean organizationEnabled, boolean chatHistoryPersistenceEnabled, Map<String, Feature> features) { }
}
