package edu.zsc.ai.local;

import edu.zsc.ai.api.controller.db.SqlExecutionController;
import edu.zsc.ai.domain.model.dto.request.db.*;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.service.db.impl.SqlExecutionServiceImpl;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/db/sql")
final class LocalSqlController {
    private final SqlExecutionController delegate;
    LocalSqlController(LocalConnectionService connections, SqlExecutionServiceImpl sql) {
        delegate = new SqlExecutionController(sql, connections, connections);
    }
    @PostMapping("/execute") ApiResponse<?> execute(@Valid @RequestBody ExecuteSqlRequest request) {
        LocalReadOnlySql.validate(request.getSql()); validateExecution(request.getExecutionId()); return delegate.executeSql(request);
    }
    @PostMapping("/execute-script") ApiResponse<?> script(@Valid @RequestBody ExecuteSqlBatchRequest request) {
        LocalReadOnlySql.validate(request.getSql()); validateExecution(request.getExecutionId());
        // C1 exposes independent read queries, not transaction modes. The entire script was checked before opening a connection.
        request.setMode(edu.zsc.ai.plugin.model.transaction.BatchMode.STEPWISE);
        return delegate.executeScript(request);
    }
    @PostMapping("/cancel") ApiResponse<?> cancel(@Valid @RequestBody CancelSqlRequest request) {
        validateExecution(request.getExecutionId()); return delegate.cancel(request);
    }
    private static void validateExecution(String id) {
        if (id != null) try { java.util.UUID.fromString(id); } catch (IllegalArgumentException e) { throw LocalRequestException.invalid(); }
    }
}
