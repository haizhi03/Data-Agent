package edu.zsc.ai.api.controller.db;

import edu.zsc.ai.domain.model.dto.request.db.SchemaCompareRequest;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.service.db.SchemaCompareService;
import edu.zsc.ai.domain.service.db.compare.diff.SchemaCompareResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 同库结构比较，只读接口。 */
@Validated
@RestController
@RequestMapping("/api/schema")
@RequiredArgsConstructor
public class SchemaCompareController {

    private static final Logger log = LoggerFactory.getLogger(SchemaCompareController.class);

    private final SchemaCompareService schemaCompareService;

    @PostMapping("/compare")
    public ApiResponse<SchemaCompareResult> compare(@Valid @RequestBody SchemaCompareRequest request) {
        log.info("Schema compare: connectionId={}, source={}.{}, target={}.{}",
                request.getConnectionId(),
                request.getSourceCatalog(), request.getSourceSchema(),
                request.getTargetCatalog(), request.getTargetSchema());
        return ApiResponse.success(schemaCompareService.compare(request));
    }
}
