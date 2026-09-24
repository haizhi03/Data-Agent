package edu.zsc.ai.api.controller.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.DeleteSequenceRequest;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.service.db.SequenceService;
import edu.zsc.ai.plugin.model.metadata.SequenceMetadata;
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
@RequestMapping("/api/sequences")
@RequiredArgsConstructor
public class SequenceController {

    private final SequenceService sequenceService;

    @GetMapping
    public ApiResponse<List<SequenceMetadata>> listSequences(
            @RequestParam @NotNull Long connectionId,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        return ApiResponse.success(sequenceService.getSequences(new DbContext(connectionId, catalog, schema)));
    }

    @GetMapping("/ddl")
    public ApiResponse<String> getSequenceDdl(
            @RequestParam @NotNull Long connectionId,
            @RequestParam @NotBlank String sequenceName,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        return ApiResponse.success(sequenceService.getSequenceDdl(
                new DbContext(connectionId, catalog, schema), sequenceName));
    }

    @DeleteMapping
    public ApiResponse<Void> deleteSequence(@Valid @RequestBody DeleteSequenceRequest request) {
        sequenceService.deleteSequence(new DbContext(
                request.getConnectionId(), request.getCatalog(), request.getSchema()), request.getSequenceName());
        return ApiResponse.success(null);
    }
}
