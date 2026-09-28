package edu.zsc.ai.api.controller.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.DeleteConstraintRequest;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.service.db.ConstraintService;
import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/constraints")
@RequiredArgsConstructor
public class ConstraintController {

    private final ConstraintService constraintService;

    @GetMapping
    public ApiResponse<List<ConstraintMetadata>> listConstraints(
            @RequestParam @NotNull Long connectionId,
            @RequestParam @NotBlank String tableName,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        return ApiResponse.success(constraintService.getConstraints(
                new DbContext(connectionId, catalog, schema), tableName));
    }

    @GetMapping("/ddl")
    public ApiResponse<String> getConstraintDdl(
            @RequestParam @NotNull Long connectionId,
            @RequestParam @NotBlank String constraintName,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        return ApiResponse.success(constraintService.getConstraintDdl(
                new DbContext(connectionId, catalog, schema), constraintName));
    }

    @DeleteMapping
    public ApiResponse<Void> deleteConstraint(@Valid @RequestBody DeleteConstraintRequest request) {
        constraintService.deleteConstraint(new DbContext(
                request.getConnectionId(), request.getCatalog(), request.getSchema()),
                request.getTableName(), request.getConstraintName());
        return ApiResponse.success(null);
    }
}
