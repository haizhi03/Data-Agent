package edu.zsc.ai.api.controller.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AnalyzeSqlRequest;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.sql.SqlScriptAnalysis;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/db/sql")
@RequiredArgsConstructor
public class SqlAnalysisController {

    private final ConnectionService connectionService;

    @PostMapping("/analyze")
    public ApiResponse<SqlScriptAnalysis> analyze(@Valid @RequestBody AnalyzeSqlRequest request) {
        DbContext db = new DbContext(request.connectionId(), request.catalog(), request.schema());
        connectionService.openConnection(db);
        String pluginId = ActiveConnectionRegistry.getOwnedConnection(db).pluginId();
        return ApiResponse.success(DefaultPluginManager.getInstance()
                .getSqlAnalyzerByPluginId(pluginId).analyze(request.sql()));
    }
}
