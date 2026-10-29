package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.model.dto.response.agent.ChatResponseBlock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
final class LocalAssistantController {
    private final LocalRuntimeIdentity identity;
    private final LocalConnectionService connections;
    private final LocalConversationRegistry sessions;
    private final LocalAssistantService assistant;
    private final LocalWriteService writes;
    LocalAssistantController(LocalRuntimeIdentity identity, LocalConnectionService connections,
            LocalConversationRegistry sessions, LocalAssistantService assistant, LocalWriteService writes) {
        this.identity = identity; this.connections = connections; this.sessions = sessions; this.assistant = assistant; this.writes=writes;
    }
    record Create(Long connectionId,
            @com.fasterxml.jackson.annotation.JsonAlias("catalog") @Size(max=200) String catalogName,
            @com.fasterxml.jackson.annotation.JsonAlias("schema") @Size(max=200) String schemaName) { }
    record Turn(@NotNull Long conversationId, @NotBlank @Size(max=16000) String message, @Size(max=256) String modelConfigId,
            edu.zsc.ai.api.model.request.ChatDefaultTarget defaultTarget,
            java.util.List<edu.zsc.ai.api.model.request.ChatUserMention> userMentions) {
        Turn(Long id, String message, String model) { this(id,message,model,null,null); }
    }
    record SessionView(long conversationId, String runtimeInstanceId) { }
    @PostMapping("/api/local/conversations") ApiResponse<?> create(@Valid @RequestBody Create request, HttpServletRequest http) {
        runtime(http);
        var target=assistant.normalizeTarget(new edu.zsc.ai.api.model.request.ChatDefaultTarget(request.connectionId(),blank(request.catalogName()),blank(request.schemaName())));
        Long version=target.getConnectionId()==null?null:connections.get(target.getConnectionId()).version();
        var session=sessions.create(new DbContext(target.getConnectionId(),target.getCatalogName(),target.getSchemaName()),version);
        return ApiResponse.success(new SessionView(session.id, identity.runtimeInstanceId().toString()));
    }
    @PostMapping(value="/api/local/chat/stream", produces="text/event-stream") Flux<ChatResponseBlock> stream(
            @Valid @RequestBody Turn request, HttpServletRequest http) {
        runtime(http); return assistant.chat(request);
    }
    @GetMapping("/api/local/conversations/{id}") ApiResponse<?> get(@PathVariable long id, HttpServletRequest http) {
        runtime(http); return ApiResponse.success(java.util.Map.of("conversationId", id, "defaultTarget", assistant.defaultTarget(id)));
    }
    @PutMapping("/api/local/conversations/{id}/default-target") ApiResponse<?> target(@PathVariable long id,
            @RequestBody edu.zsc.ai.api.model.request.ChatDefaultTarget target, HttpServletRequest http) {
        runtime(http); return ApiResponse.success(assistant.updateTarget(id,target));
    }
    @PostMapping("/api/local/conversations/{id}/compact") ApiResponse<?> compact(@PathVariable long id, HttpServletRequest http) {
        runtime(http); return ApiResponse.success(assistant.compact(id));
    }
    @PostMapping("/api/local/conversations/{id}/cancel") ApiResponse<?> cancel(@PathVariable long id, HttpServletRequest http) {
        runtime(http); sessions.cancel(id); return ApiResponse.success(java.util.Map.of("status", "CANCEL_REQUESTED"));
    }
    @DeleteMapping("/api/local/conversations/{id}") ApiResponse<?> close(@PathVariable long id, HttpServletRequest http) {
        runtime(http); sessions.close(id); return ApiResponse.success();
    }
    @GetMapping("/api/local/conversations/{id}/write-proposals") ApiResponse<?> proposals(@PathVariable long id, HttpServletRequest http) {
        runtime(http); return ApiResponse.success(sessions.proposals(id));
    }
    @PostMapping("/api/permissions/write-executions/approve") ApiResponse<?> decide(@RequestBody com.fasterxml.jackson.databind.JsonNode body, HttpServletRequest http) {
        runtime(http);
        if (body.has("grantPreset") || body.has("scopeType")) throw new LocalRequestException(409,"LOCAL_FEATURE_NOT_SUPPORTED","本地写入仅支持单次确认");
        var allowed=java.util.Set.of("conversationId","proposalId","decision");
        if (!body.isObject() || body.size()!=3) throw LocalRequestException.invalid();
        body.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw LocalRequestException.invalid(); });
        if (!body.path("conversationId").isIntegralNumber() || !body.path("conversationId").canConvertToLong()
                || !body.path("proposalId").isTextual() || !body.path("decision").isTextual()) throw LocalRequestException.invalid();
        String proposalId=body.path("proposalId").asText(),decision=body.path("decision").asText();
        try { if (!java.util.UUID.fromString(proposalId).toString().equals(proposalId)) throw LocalRequestException.invalid(); }
        catch (IllegalArgumentException e) { throw LocalRequestException.invalid(); }
        if (!decision.equals("APPROVE") && !decision.equals("REJECT")) throw LocalRequestException.invalid();
        return ApiResponse.success(writes.decide(body.path("conversationId").asLong(),proposalId,decision.equals("APPROVE")));
    }
    private void runtime(HttpServletRequest http) {
        if (java.util.Collections.list(http.getHeaders("X-Local-Runtime-Id")).size() != 1
                || !identity.runtimeInstanceId().toString().equals(http.getHeader("X-Local-Runtime-Id"))) {
            throw new LocalRequestException(409, "LOCAL_RUNTIME_MISMATCH", "本地实例已改变，请重新打开对话");
        }
    }
    private static boolean nonempty(String value) { return value != null && !value.isBlank(); }
    private static String blank(String value) { return nonempty(value) ? value.trim() : null; }
}
