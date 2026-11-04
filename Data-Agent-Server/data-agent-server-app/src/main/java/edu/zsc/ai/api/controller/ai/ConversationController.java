package edu.zsc.ai.api.controller.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.zsc.ai.agent.memory.ChatMemoryCompressor;
import edu.zsc.ai.common.converter.ai.ConversationConverter;
import edu.zsc.ai.config.ai.AiModelCatalog;
import edu.zsc.ai.config.ai.model.ModelIntegrationException;
import edu.zsc.ai.config.ai.model.ModelPurpose;
import edu.zsc.ai.config.ai.model.ModelRuntime;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.AgentRequestContextInfo;
import edu.zsc.ai.domain.model.dto.request.ai.ConversationCompactRequest;
import edu.zsc.ai.domain.model.dto.request.ai.ConversationUpdateRequest;
import edu.zsc.ai.domain.model.dto.request.base.PageRequest;
import edu.zsc.ai.domain.model.dto.response.ai.ConversationCompactResponse;
import edu.zsc.ai.domain.model.dto.response.ai.ConversationMessageResponse;
import edu.zsc.ai.domain.model.dto.response.ai.ConversationResponse;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.model.dto.response.base.PageResponse;
import edu.zsc.ai.domain.model.entity.ai.AiConversation;
import edu.zsc.ai.domain.service.ai.AiConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conversation management API.
 * All operations are scoped to the current login user.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final AiConversationService aiConversationService;
    private final ChatMemoryCompressor chatMemoryCompressor;
    private final AiModelCatalog aiModelCatalog;
    private final ModelRuntime modelRuntime;

    @GetMapping
    public ApiResponse<PageResponse<ConversationResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) Integer current,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer size) {
        PageRequest pageRequest = PageRequest.builder().current(current).size(size).build();
        Page<AiConversation> page = aiConversationService.pageByCurrentUser(pageRequest);
        List<ConversationResponse> records = page.getRecords().stream()
                .map(ConversationConverter::toResponse)
                .toList();
        PageResponse<ConversationResponse> body = PageResponse.<ConversationResponse>builder()
                .current(page.getCurrent())
                .size(page.getSize())
                .total(page.getTotal())
                .pages(page.getPages())
                .records(records)
                .build();
        return ApiResponse.success(body);
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationResponse> getById(@PathVariable @NotNull Long id) {
        AiConversation conversation = aiConversationService.getByIdForCurrentUser(id);
        return ApiResponse.success(ConversationConverter.toResponse(conversation));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<ConversationMessageResponse>> getMessages(@PathVariable @NotNull Long id) {
        List<ConversationMessageResponse> messages = aiConversationService.getMessagesForCurrentUser(id);
        return ApiResponse.success(messages);
    }

    @PostMapping("/{id}")
    public ApiResponse<ConversationResponse> update(
            @PathVariable @NotNull Long id,
            @Valid @RequestBody ConversationUpdateRequest request) {
        AiConversation updated = aiConversationService.updateTitle(id, request.getTitle());
        return ApiResponse.success(ConversationConverter.toResponse(updated));
    }

    @PostMapping("/{id}/compact")
    public ApiResponse<ConversationCompactResponse> compact(
            @PathVariable @NotNull Long id,
            @Valid @RequestBody ConversationCompactRequest request) {
        if (modelRuntime.enabled()) {
            var run = modelRuntime.prepare(request.getModelConfigId(), request.getModel());
            var previous = AgentRequestContext.snapshot();
            try {
                AgentRequestContext.set(AgentRequestContextInfo.builder().modelRunSnapshot(run).build());
                return compactResponse(id, run.model(ModelPurpose.MAIN_CHAT).modelConfigId());
            } finally {
                if (previous == null) AgentRequestContext.clear();
                else AgentRequestContext.set(previous);
            }
        }
        if (request.getModelConfigId() != null && !request.getModelConfigId().isBlank()) throw ModelIntegrationException.unavailable();
        final String modelName;
        try {
            modelName = aiModelCatalog.resolve(request.getModel()).getModelName();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        return compactResponse(id, modelName);
    }

    private ApiResponse<ConversationCompactResponse> compactResponse(Long id, String modelName) {
        var result = chatMemoryCompressor.compressNow(id, modelName);
        return ApiResponse.success(ConversationCompactResponse.builder()
                .compressed(result.memoryCompressed())
                .tokenCountBefore(result.tokenCountBefore())
                .tokenCountAfter(result.tokenCountAfter())
                .compressedMessageCount(result.compressedMessageCount())
                .keptRecentCount(result.keptRecentCount())
                .build());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @NotNull Long id) {
        aiConversationService.deleteByCurrentUser(id);
        return ApiResponse.success();
    }
}
