package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.zsc.ai.api.model.request.ChatDefaultTarget;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.agent.ChatResponseBlock;
import edu.zsc.ai.domain.service.agent.ChatReferenceService;
import edu.zsc.ai.domain.service.agent.ChatTurnContext;
import edu.zsc.ai.domain.service.agent.runtimecontext.strategy.ExplicitReferencesStrategy;
import edu.zsc.ai.domain.service.ai.model.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.*;

/**
 * Experimental Pi engine: Java starts a pi-agent-core Node worker per temporary
 * conversation and bridges its tool calls to the shared local metadata reads.
 * The worker owns the model calls and the in-memory transcript; Java owns target
 * and reference validation, credentials, tool execution, cancellation and the
 * existing SSE event format. Enabled only through app.local.pi.enabled.
 */
final class LocalPiChatService implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalPiChatService.class);
    private static final Set<ModelApiStyle> SUPPORTED = Set.of(
            ModelApiStyle.OPENAI_CHAT_COMPLETIONS, ModelApiStyle.ANTHROPIC_MESSAGES);
    private static final int MAX_OUTPUT_CHARS = 64000;
    private static final int MAX_TOOL_RESULT_CHARS = 12000;
    private static final int MAX_TOOL_CALLS = 32;

    static final String SYSTEM_PROMPT = edu.zsc.ai.config.ai.PromptConfig.loadClassPathResource("prompt/pi-main-agent_zh.md");

    private final ModelRuntime models;
    private final CredentialService credentials;
    private final ModelConfigResolver resolver;
    private final ModelManagementService management;
    private final LocalConversationRegistry sessions;
    private final LocalConnectionService connections;
    private final ChatReferenceService references;
    private final ExplicitReferencesStrategy explicitReferences;
    private final LocalMetadataTools metadataTools;
    private final LocalReadOnlyQuery queries;
    private final LocalPiExplorer explorer;
    private final ObjectMapper json;
    private final LocalPiProperties properties;
    private final Map<Long, PiWorkerProcess> workers = new HashMap<>();
    private final Map<Long, RunHandler> activeRuns = new HashMap<>();
    private final ExecutorService toolExecutor;
    private final ScheduledExecutorService cancelGuard;

    LocalPiChatService(ModelRuntime models, CredentialService credentials, ModelConfigResolver resolver,
            ModelManagementService management, LocalConversationRegistry sessions, LocalConnectionService connections,
            ChatReferenceService references, ExplicitReferencesStrategy explicitReferences,
            LocalMetadataTools metadataTools, LocalReadOnlyQuery queries, ObjectMapper json, LocalPiProperties properties) {
        this.models = models; this.credentials = credentials; this.resolver = resolver; this.management = management;
        this.sessions = sessions; this.connections = connections; this.references = references;
        this.explicitReferences = explicitReferences; this.metadataTools = metadataTools; this.queries = queries; this.json = json;
        this.properties = properties;
        this.explorer = new LocalPiExplorer(properties,json,credentials,resolver,metadataTools);
        this.toolExecutor = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "pi-tool-" + runnable.hashCode()); thread.setDaemon(true); return thread;
        });
        this.cancelGuard = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pi-cancel-guard"); thread.setDaemon(true); return thread;
        });
        sessions.setSessionGoneListener(this::closeConversation);
    }

    LocalRuntimeController.Feature capability() {
        if (!properties.enabled()) return new LocalRuntimeController.Feature("UNAVAILABLE", "PI_DISABLED");
        if (!java.nio.file.Files.isRegularFile(properties.resolveWorkerScript()))
            return new LocalRuntimeController.Feature("UNAVAILABLE", "PI_WORKER_NOT_FOUND");
        try {
            if (!management.status().credentialStorageReady()) return new LocalRuntimeController.Feature("UNAVAILABLE", "MODEL_MANAGEMENT_UNAVAILABLE");
            if (resolver.listAvailable().isEmpty()) return new LocalRuntimeController.Feature("UNCONFIGURED", "MODEL_NOT_CONFIGURED");
            var run = models.prepare(null, null); // Stored configuration and credentials only; no upstream call.
            if (!SUPPORTED.contains(run.model(ModelPurpose.MAIN_CHAT).apiStyle()))
                return new LocalRuntimeController.Feature("UNCONFIGURED", "PI_UNSUPPORTED_API_STYLE");
            for (var purpose : ModelPurpose.values()) credentials.withSecret(run.model(purpose), ignored -> Boolean.TRUE);
            return new LocalRuntimeController.Feature("READY", null);
        } catch (ModelManagementException e) { return new LocalRuntimeController.Feature("UNAVAILABLE", "MODEL_MANAGEMENT_UNAVAILABLE"); }
        catch (ModelIntegrationException e) { return new LocalRuntimeController.Feature("UNCONFIGURED", "MODEL_NOT_CONFIGURED"); }
    }

    Flux<ChatResponseBlock> chat(LocalAssistantController.Turn input) {
        if (!properties.enabled()) throw new LocalRequestException(409, "LOCAL_FEATURE_NOT_SUPPORTED", "Pi 实验入口未启用");
        var session = sessions.require(input.conversationId());
        var run = models.prepare(input.modelConfigId(), null);
        var saved = sessions.target(session.id);
        var target = references.normalizeTarget(input.defaultTarget() == null ? storedTarget(session.id) : input.defaultTarget());
        var mentions = references.validateMentions(input.message(), input.userMentions());
        var db = new DbContext(target.getConnectionId(), target.getCatalogName(), target.getSchemaName());
        Long version = db.connectionId() == null ? null : db.equals(saved.target()) ? saved.version() : connections.get(db.connectionId()).version();
        var config = run.model(ModelPurpose.MAIN_CHAT);
        if (!SUPPORTED.contains(config.apiStyle()))
            throw new LocalRequestException(409, "PI_UNSUPPORTED_API_STYLE", "Pi 实验入口暂不支持该模型接口类型: " + config.apiStyle());
        sessions.begin(session, run, LocalConversationRegistry.Engine.PI);
        session.modelConfigId = config.modelConfigId();
        log.info("Pi chat started: conversationId={}, runId={}, modelConfigId={}, messageChars={}",
                session.id, run.runId(), config.modelConfigId(), input.message().length());
        final DbContext defaultTarget = db;
        final Map<Long, Long> versions = new ConcurrentHashMap<>();
        if (db.connectionId() != null && version != null) versions.put(db.connectionId(), version);
        try {
            for (var mention : mentions) versions.computeIfAbsent(mention.getConnectionId(), id -> connections.get(id).version());
            String enriched = ChatTurnContext.encode(input.message(), target, mentions, explicitReferences.renderReferences(mentions, "zh"));
            var start = credentials.withSecret(config, secret -> startMessage(session, run, config, secret, enriched, target, mentions));
            var requestContext = RequestContext.snapshot().toBuilder().conversationId(session.id)
                    .connectionId(db.connectionId()).catalog(db.catalog()).schema(db.schema()).build();
            var agentContext = AgentRequestContextInfo.builder().modelRunSnapshot(run).agentMode("agent").agentType("main").build();
            return stream(session, run, defaultTarget, versions, requestContext, agentContext, start);
        } catch (RuntimeException e) {
            sessions.finish(session, run, null);
            throw e;
        }
    }

    private ChatDefaultTarget storedTarget(long id) {
        var target = sessions.target(id).target();
        return new ChatDefaultTarget(target.connectionId(), target.catalog(), target.schema());
    }

    private Map<String, Object> startMessage(LocalConversationRegistry.Session session, ModelRunSnapshot run,
            ResolvedModelConfig config, String secret, String enriched, ChatDefaultTarget target,
            List<edu.zsc.ai.api.model.request.ChatUserMention> mentions) {
        var model = new LinkedHashMap<String, Object>();
        model.put("apiStyle", config.apiStyle().name());
        model.put("baseUrl", config.endpoint().toString());
        model.put("modelId", config.modelId());
        model.put("contextWindow", config.maxContextTokens());
        if (config.options() != null && config.options().maxOutputTokens() != null)
            model.put("maxOutputTokens", config.options().maxOutputTokens());
        var message = new LinkedHashMap<String, Object>();
        message.put("type", "start");
        message.put("runId", run.runId());
        message.put("sessionId", "conversation-" + session.id);
        message.put("systemPrompt", SYSTEM_PROMPT);
        message.put("message", enriched);
        message.put("model", model);
        message.put("apiKey", secret); // stdin pipe only; never logged or placed on the command line
        var context = new LinkedHashMap<String, Object>();
        context.put("defaultTarget", target);
        context.put("mentions", mentions);
        message.put("context", context);
        message.put("limits",Map.of("maxModelCalls",8,"maxToolCalls",32));
        return message;
    }

    private Flux<ChatResponseBlock> stream(LocalConversationRegistry.Session session, ModelRunSnapshot run,
            DbContext defaultTarget, Map<Long, Long> versions, RequestContextInfo requestContext,
            AgentRequestContextInfo agentContext, Map<String, Object> start) {
        long startedNanos = System.nanoTime();
        return Flux.<ChatResponseBlock>create(sink -> {
            var handler = new RunHandler(session, run, sink, defaultTarget, versions, requestContext, agentContext, startedNanos);
            synchronized (this) {
                activeRuns.put(session.id, handler);
            }
            sink.onCancel(() -> {
                log.warn("Pi chat stream cancelled by client: conversationId={}, runId={}", session.id, run.runId());
                handler.cancelRequested = true;
                run.cancel();
                handler.terminate("CANCELLED", "AGENT_RUN_CANCELLED", null);
            });
            try {
                PiWorkerProcess worker = workerFor(session.id);
                handler.worker = worker;
                run.track(() -> {
                    handler.cancelRequested = true;
                    worker.send(Map.of("type", "cancel", "runId", run.runId()));
                    cancelGuard.schedule(() -> {
                        if (!handler.terminal.get()) {
                            log.warn("Pi worker did not settle after cancel; killing: conversationId={}, runId={}", session.id, run.runId());
                            closeWorker(session.id);
                            handler.terminate("CANCELLED", null, null);
                        }
                    }, properties.cancelGraceSeconds(), TimeUnit.SECONDS);
                });
                sink.next(ChatResponseBlock.builder().type("STATUS").data("RUNNING").conversationId(session.id).build());
                worker.send(start);
            } catch (RuntimeException e) {
                log.error("Pi run failed to start: conversationId={}, runId={}", session.id, run.runId(), e);
                handler.terminate("FAILED", errorCode(e), null);
            }
        }, FluxSink.OverflowStrategy.BUFFER).timeout(Duration.ofSeconds(150))
                .onErrorResume(error -> {
                    log.error("Pi chat SSE failed: conversationId={}, runId={}", session.id, run.runId(), error);
                    run.cancel();
                    sessions.finish(session, run, null);
                    removeRun(session.id);
                    String code = error instanceof java.util.concurrent.TimeoutException ? "LOCAL_STREAM_TIMEOUT" : "LOCAL_STREAM_FAILED";
                    var block = ChatResponseBlock.doneBlock(Map.of("status", "FAILED", "runId", run.runId(), "errorCode", code));
                    block.setConversationId(session.id);
                    return Flux.just(block);
                });
    }

    private synchronized PiWorkerProcess workerFor(long conversationId) {
        var existing = workers.get(conversationId);
        if (existing != null && existing.isAlive()) return existing;
        workers.remove(conversationId);
        var listener = new PiWorkerProcess.Listener() {
            @Override public void onMessage(JsonNode message) {
                RunHandler handler;
                synchronized (LocalPiChatService.this) { handler = activeRuns.get(conversationId); }
                if (handler == null) {
                    log.info("Pi worker event without active run dropped: conversationId={}, type={}", conversationId, message.path("type").asText());
                    return;
                }
                handler.onMessage(message);
            }
            @Override public void onGone(String reasonCode) {
                synchronized (LocalPiChatService.this) { workers.remove(conversationId); }
                RunHandler handler;
                synchronized (LocalPiChatService.this) { handler = activeRuns.get(conversationId); }
                if (handler != null) {
                    log.warn("Pi worker gone during run: conversationId={}, runId={}, reason={}", conversationId, handler.run.runId(), reasonCode);
                    handler.terminate(handler.cancelRequested ? "CANCELLED" : "FAILED",
                            handler.cancelRequested ? null : reasonCode, null);
                }
            }
        };
        var worker = PiWorkerProcess.start(conversationId, properties, json, listener);
        workers.put(conversationId, worker);
        return worker;
    }

    private synchronized void removeRun(long conversationId) { activeRuns.remove(conversationId); }

    private synchronized void closeWorker(long conversationId) {
        var worker = workers.remove(conversationId);
        if (worker != null) worker.close();
    }

    /** Conversation closed or evicted: release the worker and fail any active run. */
    void closeConversation(long conversationId) {
        RunHandler handler;
        synchronized (this) { handler = activeRuns.get(conversationId); }
        if (handler != null) handler.terminate(handler.cancelRequested ? "CANCELLED" : "FAILED",
                handler.cancelRequested ? null : "LOCAL_SESSION_EXPIRED", null);
        closeWorker(conversationId);
    }

    record ToolResult(String text, boolean isError) { }

    private ToolResult executeTool(LocalConversationRegistry.Session session, ModelRunSnapshot run, DbContext defaultTarget,
            Map<Long, Long> versions, RequestContextInfo request, AgentRequestContextInfo agent,
            String name, String arguments) throws java.io.IOException {
        JsonNode args;
        try {
            args = json.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (Exception e) {
            throw LocalRequestException.invalid();
        }
        if (args == null || !args.isObject()) throw LocalRequestException.invalid();
        if (name.equals("list_connections")) {
            if (!args.isEmpty()) throw LocalRequestException.invalid();
            try (var ignored = new ModelContextScope(request, agent)) {
                run.ensureActive();
                if (session.closed) throw LocalRequestException.forbidden();
                String text = json.writeValueAsString(metadataTools.listConnections(versions));
                run.ensureActive();
                return new ToolResult(text.length() <= MAX_TOOL_RESULT_CHARS ? text
                        : json.writeValueAsString(Map.of("truncated", true, "preview", text.substring(0, 11000))), false);
            }
        }
        var allowed = new HashSet<>(List.of("connectionId", "catalogName", "schemaName"));
        switch (name) {
            case "list_tables", "list_namespaces" -> { }
            case "describe_table" -> allowed.add("tableName");
            case "describe_tables" -> allowed.add("tableNames");
            case "query_database" -> allowed.add("query");
            default -> throw LocalRequestException.invalid();
        }
        args.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw LocalRequestException.invalid(); });
        if (args.hasNonNull("connectionId") && (!args.path("connectionId").isIntegralNumber()
                || !args.path("connectionId").canConvertToLong() || args.path("connectionId").asLong() <= 0
                || args.path("connectionId").asLong() > 9007199254740991L)) throw LocalRequestException.invalid();
        for (String field : List.of("catalogName", "schemaName", "tableName", "query"))
            if (args.hasNonNull(field) && !args.path(field).isTextual()) throw LocalRequestException.invalid();
        if (name.equals("query_database") && (!args.hasNonNull("query") || args.path("query").asText().length() > 65536))
            throw LocalRequestException.invalid();
        var tableNames=new ArrayList<String>();
        if(name.equals("describe_tables")) {
            var values=args.path("tableNames");
            if(!values.isArray() || values.isEmpty() || values.size()>4)throw LocalRequestException.invalid();
            for(var value:values) { if(!value.isTextual())throw LocalRequestException.invalid();tableNames.add(value.asText()); }
        }
        Long connectionId = args.hasNonNull("connectionId") ? args.path("connectionId").asLong() : null;
        String catalogName = args.hasNonNull("catalogName") ? args.path("catalogName").asText(null) : null;
        String schemaName = args.hasNonNull("schemaName") ? args.path("schemaName").asText(null) : null;
        var probe = LocalMetadataTools.fillDefaults(connectionId, catalogName, schemaName, defaultTarget);
        try (var ignored = new ModelContextScope(request.toBuilder().connectionId(probe.connectionId())
                .catalog(probe.catalog()).schema(probe.schema()).build(), agent)) {
            run.ensureActive();
            if (session.closed) throw LocalRequestException.forbidden();
            var scope = metadataTools.resolveValidated(probe, versions);
            if (name.equals("query_database")) {
                var result = metadataTools.withPinnedVersion(scope,
                        () -> queries.execute(session.id, run, scope.target(), args.path("query").asText()));
                return queryResult(scope.target(), result);
            }
            Object body = metadataTools.withPinnedVersion(scope, () -> switch (name) {
                case "list_namespaces" -> metadataTools.listNamespaces(scope.target());
                case "list_tables" -> metadataTools.listTables(scope.target());
                case "describe_tables" -> metadataTools.describeTables(scope.target(),tableNames,run::ensureActive);
                default -> metadataTools.describeTable(scope.target(), args.hasNonNull("tableName") ? args.path("tableName").asText() : null);
            });
            run.ensureActive();
            String text = json.writeValueAsString(body);
            return new ToolResult(text.length() <= MAX_TOOL_RESULT_CHARS
                    ? text : json.writeValueAsString(Map.of("truncated", true, "preview", text.substring(0, 11000))), false);
        }
    }

    private ToolResult queryResult(DbContext target, LocalReadOnlyQuery.Result query) throws java.io.IOException {
        var result = query.response();
        var body = new LinkedHashMap<String, Object>();
        body.put("target", LocalMetadataTools.identity(target));
        body.put("executionId", query.executionId());
        body.put("success", result.isSuccess());
        body.put("statementState", result.getStatementState());
        body.put("transactionOutcome", result.getTransactionOutcome());
        body.put("batchState", result.getBatchState());
        body.put("maxRows", LocalReadOnlyQuery.MAX_ROWS);
        body.put("timeoutMs", LocalReadOnlyQuery.TIMEOUT_MS);
        body.put("truncated", result.getResultSet() != null && Boolean.TRUE.equals(result.getResultSet().getTruncated())
                || result.getExecutionInfo() != null && Boolean.TRUE.equals(result.getExecutionInfo().getTruncated()));
        body.put("result", result);
        String text = json.writeValueAsString(body);
        if (text.length() > MAX_TOOL_RESULT_CHARS) {
            body.remove("result");
            body.put("truncated", true);
            body.put("preview", text.substring(0, 10000));
            text = json.writeValueAsString(body);
            // Escaping the preview may expand it; keep the envelope and its execution facts intact.
            while (text.length() > MAX_TOOL_RESULT_CHARS) {
                String preview = (String) body.get("preview");
                if (preview.isEmpty()) throw LocalRequestException.invalid();
                body.put("preview", preview.substring(0, preview.length() / 2));
                text = json.writeValueAsString(body);
            }
        }
        return new ToolResult(text, !result.isSuccess());
    }

    private String toolError(Throwable e) {
        try {
            return json.writeValueAsString(Map.of("error", errorCode(e)));
        } catch (Exception unexpected) {
            return "{\"error\":\"LOCAL_TOOL_FAILED\"}";
        }
    }

    static String errorCode(Throwable e) {
        for (int depth = 0; e != null && depth < 8; depth++, e = e.getCause()) {
            if (e instanceof ModelIntegrationException known) return known.getReason();
            if (e instanceof LocalRequestException known) return known.response().reasonCode();
        }
        return "PI_WORKER_ERROR";
    }

    /** Per-run bridge between worker events and the SSE sink. Emits exactly one terminal block. */
    private final class RunHandler {
        final LocalConversationRegistry.Session session;
        final ModelRunSnapshot run;
        final FluxSink<ChatResponseBlock> sink;
        final DbContext defaultTarget;
        final Map<Long, Long> versions;
        final RequestContextInfo requestContext;
        final AgentRequestContextInfo agentContext;
        final long startedNanos;
        final AtomicBoolean terminal = new AtomicBoolean();
        final AtomicInteger outputChars = new AtomicInteger();
        final AtomicInteger toolCalls = new AtomicInteger();
        final AtomicInteger explorerTasks = new AtomicInteger();
        final Set<String> claimedToolCalls = ConcurrentHashMap.newKeySet();
        final List<JsonNode> childUsage = new CopyOnWriteArrayList<>();
        volatile boolean cancelRequested;
        volatile PiWorkerProcess worker;

        RunHandler(LocalConversationRegistry.Session session, ModelRunSnapshot run, FluxSink<ChatResponseBlock> sink,
                DbContext defaultTarget, Map<Long, Long> versions, RequestContextInfo requestContext,
                AgentRequestContextInfo agentContext, long startedNanos) {
            this.session = session; this.run = run; this.sink = sink; this.defaultTarget = defaultTarget;
            this.versions = versions; this.requestContext = requestContext; this.agentContext = agentContext;
            this.startedNanos = startedNanos;
        }

        void onMessage(JsonNode message) {
            if (terminal.get()) return; // late events never write to a finished run
            if (!run.runId().equals(message.path("runId").asText())) return; // late events from a previous run
            switch (message.path("type").asText()) {
                case "text_delta" -> {
                    String delta = message.path("delta").asText("");
                    if (delta.isEmpty()) return;
                    if (outputChars.addAndGet(delta.length()) > MAX_OUTPUT_CHARS) {
                        run.cancel();
                        terminate("FAILED", "AGENT_BUDGET_EXCEEDED", null);
                        return;
                    }
                    sink.next(ChatResponseBlock.text(delta));
                }
                case "tool_call" -> onToolCall(message);
                case "finished" -> {
                    String status = switch (message.path("status").asText("")) {
                        case "SUCCEEDED", "CANCELLED" -> message.path("status").asText();
                        default -> "FAILED";
                    };
                    String code = message.hasNonNull("errorCode") ? message.path("errorCode").asText() : null;
                    if (cancelRequested) { status = "CANCELLED"; code = null; }
                    terminate(status, code, message.path("usage"));
                }
                case "error" -> terminate("FAILED", "PI_PROTOCOL_ERROR", null);
                default -> log.info("Pi worker message ignored: conversationId={}, type={}", session.id, message.path("type").asText());
            }
        }

        private void onToolCall(JsonNode message) {
            String toolCallId = message.path("toolCallId").asText();
            String name = message.path("name").asText();
            String arguments = message.path("arguments").asText("{}");
            if(toolCallId.isBlank() || toolCallId.length()>200 || name.length()>100 || arguments.length()>65536 || !claimedToolCalls.add(toolCallId)) {
                terminate("FAILED","PI_PROTOCOL_ERROR",null);return;
            }
            sink.next(ChatResponseBlock.toolCall(toolCallId, name, arguments));
            String loggedName = name.replaceAll("[^A-Za-z0-9_]", "_");
            long toolStarted = System.nanoTime();
            log.info("Pi tool started: conversationId={}, runId={}, toolCallId={}, tool={}",
                    session.id, run.runId(), toolCallId.replaceAll("[^A-Za-z0-9_/-]", "_"), loggedName);
            toolExecutor.execute(() -> {
                if (terminal.get()) return;
                String result;
                boolean isError;
                try {
                    if (toolCalls.incrementAndGet() > MAX_TOOL_CALLS)
                        throw new ModelIntegrationException("AGENT_BUDGET_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS);
                    ToolResult outcome;
                    if(name.equals("callingExplorerSubAgent")) {
                        try(var ignored=new ModelContextScope(requestContext,agentContext)) {
                            outcome=explorer.execute(session.id,run,defaultTarget,versions,arguments,toolCallId,explorerTasks,
                                    (child,target,tool,args)->executeTool(session,child,target,versions,requestContext,
                                            agentContext.toBuilder().modelRunSnapshot(child).agentType("explorer").allowedConnectionIds(List.of(target.connectionId())).build(),tool,args),
                                    block->{if(!terminal.get())sink.next(block);},childUsage::add);
                        }
                    } else outcome = executeTool(session, run, defaultTarget, versions, requestContext, agentContext, name, arguments);
                    result = outcome.text();
                    isError = outcome.isError();
                } catch (Exception e) {
                    result = toolError(e);
                    isError = true;
                    log.warn("Pi tool failed: conversationId={}, runId={}, tool={}, errorCode={}",
                            session.id, run.runId(), loggedName, errorCode(e));
                }
                log.info("Pi tool finished: conversationId={}, runId={}, tool={}, isError={}, elapsedMs={}",
                        session.id, run.runId(), loggedName, isError, (System.nanoTime()-toolStarted)/1_000_000);
                if (terminal.get()) return;
                sink.next(ChatResponseBlock.toolResult(toolCallId, name, result, isError));
                var worker = this.worker;
                if (worker != null) {
                    var response = new LinkedHashMap<String, Object>();
                    response.put("type", "tool_result");
                    response.put("runId", run.runId());
                    response.put("toolCallId", toolCallId);
                    response.put("isError", isError);
                    response.put("result", result);
                    worker.send(response);
                }
            });
        }

        void terminate(String status, String code, JsonNode usage) {
            if (!terminal.compareAndSet(false, true)) return; // exactly one final state per run
            if (!status.equals("SUCCEEDED")) run.cancel(); // also release a query when the worker crashes
            removeRun(session.id);
            boolean accepted = sessions.finish(session, run, status.equals("SUCCEEDED") ? session.messages : null);
            if (status.equals("SUCCEEDED") && !accepted) { status = "CANCELLED"; code = "AGENT_RUN_CANCELLED"; }
            log.info("Pi chat finished: conversationId={}, runId={}, status={}, errorCode={}, elapsedMs={}, toolCalls={}, explorerTasks={}",
                    session.id, run.runId(), status, code, (System.nanoTime()-startedNanos)/1_000_000, toolCalls.get(), explorerTasks.get());
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("statistics", statistics(usage));
            metadata.put("elapsedMs", (System.nanoTime() - startedNanos) / 1_000_000);
            metadata.put("status", status);
            metadata.put("runId", run.runId());
            metadata.put("engine", "PI");
            if (code != null) metadata.put("errorCode", code);
            var block = ChatResponseBlock.doneBlock(metadata);
            block.setConversationId(session.id);
            sink.next(block);
            sink.complete();
        }

        private Map<String, Object> statistics(JsonNode usage) {
            var statistics = new LinkedHashMap<String, Object>();
            var reports=new ArrayList<>(childUsage);if(usage!=null)reports.add(usage);
            for(String field:List.of("modelCalls","toolCalls","inputTokens","outputTokens"))
                statistics.put(field,reports.stream().mapToLong(report->report.path(field).asLong(0)).sum());
            statistics.put("usageComplete",usage!=null && childUsage.size()==explorerTasks.get()
                    && reports.stream().allMatch(report->report.path("usageComplete").asBoolean(false)));
            return statistics;
        }
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() {
        List<PiWorkerProcess> all;
        List<RunHandler> running;
        synchronized (this) {
            all = new ArrayList<>(workers.values());
            running = new ArrayList<>(activeRuns.values());
            workers.clear();
            activeRuns.clear();
        }
        running.forEach(handler->handler.terminate("CANCELLED",null,null));
        all.forEach(PiWorkerProcess::close);
        toolExecutor.shutdownNow();
        cancelGuard.shutdownNow();
    }
}
