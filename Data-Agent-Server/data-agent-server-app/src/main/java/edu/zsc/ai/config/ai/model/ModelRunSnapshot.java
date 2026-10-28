package edu.zsc.ai.config.ai.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/** Fixed purpose bindings and a shared upper bound for every SDK invocation in a foreground run. */
public final class ModelRunSnapshot {
    private final String runId = UUID.randomUUID().toString();
    private final Map<ModelPurpose, ResolvedModelConfig> models;
    private final AtomicInteger calls = new AtomicInteger();
    private final int maxCalls;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Runnable> cancellations = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ToolOutcome> tools = new ConcurrentHashMap<>();
    private final AtomicInteger toolCount = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong inputTokens=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong outputTokens=new java.util.concurrent.atomic.AtomicLong();
    private final AtomicInteger usageReports=new AtomicInteger();
    private final AtomicBoolean usageFieldsComplete=new AtomicBoolean(true);
    public void recordUsage(dev.langchain4j.model.output.TokenUsage usage) {
        if (usage==null) return;
        if (usage.inputTokenCount()!=null) inputTokens.addAndGet(usage.inputTokenCount());
        if (usage.outputTokenCount()!=null) outputTokens.addAndGet(usage.outputTokenCount());
        if (usage.inputTokenCount()==null || usage.outputTokenCount()==null) usageFieldsComplete.set(false);
        usageReports.incrementAndGet();
    }
    public Map<String,Object> statistics() {
        return Map.of("modelCalls",calls.get(),"toolCalls",toolCount.get(),"inputTokens",inputTokens.get(),
                "outputTokens",outputTokens.get(),"usageReports",usageReports.get(),"usageComplete",usageFieldsComplete.get() && usageReports.get()==calls.get() && calls.get()>0);
    }

    public ModelRunSnapshot(Map<ModelPurpose, ResolvedModelConfig> models, int maxCalls) {
        this.models = Map.copyOf(models);
        this.maxCalls = maxCalls;
    }
    public String runId() { return runId; }
    public ResolvedModelConfig model(ModelPurpose purpose) { return models.get(purpose); }
    public void beforeCall() {
        ensureActive();
        if (calls.incrementAndGet() > maxCalls) {
            throw new ModelIntegrationException("AGENT_BUDGET_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS);
        }
    }
    public void ensureActive() {
        if (cancelled.get()) throw new ModelIntegrationException("AGENT_RUN_CANCELLED", HttpStatus.CONFLICT);
    }
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancellations.forEach(action -> { try { action.run(); } catch (RuntimeException ignored) { /* Continue cancelling remaining requests. */ } });
            cancellations.clear();
        }
    }
    public interface CancellationRegistration extends AutoCloseable { @Override void close(); }
    public CancellationRegistration track(Runnable action) {
        ensureActive();
        cancellations.add(action);
        if (cancelled.get()) { cancellations.remove(action); action.run(); ensureActive(); }
        return () -> cancellations.remove(action);
    }
    /** In-process deduplication; failures remain claimed, so an uncertain write is never replayed. */
    public ToolExecutionResult executeTool(UUID invocationId,
            ToolExecutionRequest request,
            Supplier<ToolExecutionResult> action) {
        ensureActive();
        if (invocationId == null || request.id() == null || request.id().isBlank()) throw ModelIntegrationException.capability();
        String key = invocationId + "/" + request.id();
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((request.name() + "\n" + request.arguments()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
        ToolOutcome candidate = new ToolOutcome(digest, new CompletableFuture<>());
        ToolOutcome existing = tools.putIfAbsent(key, candidate);
        if (existing != null) {
            if (!existing.digest().equals(digest)) throw new ModelIntegrationException("TOOL_VALIDATION_FAILED", HttpStatus.BAD_REQUEST);
            return existing.result().join();
        }
        try {
            if (toolCount.incrementAndGet() > 256) throw new ModelIntegrationException("AGENT_BUDGET_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS);
            ensureActive();
            var result = action.get();
            candidate.result().complete(result);
            return result;
        } catch (RuntimeException | Error error) { candidate.result().completeExceptionally(error); throw error; }
    }
    private record ToolOutcome(String digest, CompletableFuture<ToolExecutionResult> result) {}
    @Override public String toString() { return "ModelRunSnapshot[runId=" + runId + "]"; }
}
