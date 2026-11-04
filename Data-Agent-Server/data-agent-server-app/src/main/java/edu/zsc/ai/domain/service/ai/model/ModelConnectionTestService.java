package edu.zsc.ai.domain.service.ai.model;

import com.github.benmanes.caffeine.cache.*;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.dto.request.ai.ModelManagementRequests;
import edu.zsc.ai.domain.model.dto.response.ai.ModelManagementResponses.ConnectionTest;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Bounded synchronous draft tests; no config, credential or diagnostic result is persisted. */
@Service
public class ModelConnectionTestService {
    private final ModelManagementService management;
    private final FileModelStore files;
    private final MultiProviderProperties properties;
    private final CredentialService credentials;
    private final ModelConnectionProbe probe;
    private final Duration timeout;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> { var thread = new Thread(r, "model-connection-test"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    private final Cache<String,Deque<Instant>> rates = Caffeine.newBuilder().maximumSize(4096).expireAfterAccess(Duration.ofMinutes(2)).build();
    private static final Set<String> SAFE_CODES = Set.of("MODEL_AUTH_FAILED", "MODEL_RATE_LIMITED", "MODEL_UPSTREAM_TIMEOUT",
            "MODEL_UPSTREAM_UNAVAILABLE", "MODEL_PROTOCOL_ERROR", "MODEL_OUTPUT_TRUNCATED", "MODEL_REFUSED", "MODEL_CAPABILITY_UNSUPPORTED",
            "MODEL_CONFIG_CONFLICT", "MODEL_CONFIG_NOT_FOUND", "MODEL_MANAGEMENT_FORBIDDEN", "MODEL_MANAGEMENT_UNAVAILABLE");

    @Autowired
    public ModelConnectionTestService(ModelManagementService management, FileModelStore files, MultiProviderProperties properties,
                                      CredentialService credentials, ModelConnectionProbe probe) {
        this(management, files, properties, credentials, probe, Duration.ofSeconds(25));
    }
    ModelConnectionTestService(ModelManagementService management, FileModelStore files, MultiProviderProperties properties,
                               CredentialService credentials, ModelConnectionProbe probe, Duration timeout) {
        this.management=management;this.files=files;this.properties=properties;this.credentials=credentials;this.probe=probe;this.timeout=timeout;
    }

    public ConnectionTest test(ModelManagementRequests.ConnectionTest input) {
        var scope = management.scope(true);
        validate(input);
        var config = configuration(scope, input);
        limit(scope);
        var context = RequestContext.snapshot();
        var run = new ModelRunSnapshot(Map.of(ModelPurpose.MAIN_CHAT, config), 1);
        long started = System.nanoTime();
        Future<ConnectionTest> future = null;
        try {
            future = workers.submit(() -> {
                try (var ignored = new ModelContextScope(context, AgentRequestContextInfo.builder().modelRunSnapshot(run).build())) {
                    recheck(scope, input);
                    String secret = input.apiKey() != null ? input.apiKey() : credentials.withSecret(config, value -> value);
                    run.beforeCall();
                    probe.test(config, secret);
                    run.ensureActive();
                    recheck(scope, input);
                    return result(true, "MODEL_TEST_OK", started);
                } catch (RuntimeException e) { return result(false, code(e), started); }
            });
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) { return result(false, "MODEL_TEST_BUSY", started); }
        catch (TimeoutException e) { return result(false, "MODEL_UPSTREAM_TIMEOUT", started); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return result(false, "MODEL_TEST_INTERRUPTED", started); }
        catch (ExecutionException e) { return result(false, "MODEL_TEST_FAILED", started); }
        finally { run.cancel(); if (future != null && !future.isDone()) future.cancel(true); }
    }

    private ResolvedModelConfig configuration(ModelAccessContext scope, ModelManagementRequests.ConnectionTest input) {
        var existing = input.connectionId() == null ? null : saved(scope, input);
        if (input.apiKey() == null && (existing == null || existing.credentialId() == null)) {
            throw new ModelManagementException("MODEL_TEST_CREDENTIAL_REQUIRED", HttpStatus.BAD_REQUEST);
        }
        var profile = ModelProfiles.all().stream().filter(p -> p.apiStyle() == input.apiStyle()
                && p.compatibilityProfile().equals(input.compatibilityProfile())
                && p.thinkingModes().contains(MultiProviderProperties.ThinkingMode.DISABLED)).findFirst().orElseThrow(ModelManagementException::invalid);
        Set<String> allowed = new HashSet<>(properties.getModelRouting().getAllowedPrivateHosts());
        if (existing != null) allowed.addAll(existing.allowedPrivateHosts());
        String id = "connection-test/" + UUID.randomUUID();
        return new ResolvedModelConfig(id, 1, existing == null ? id : existing.id(), existing == null ? 1 : existing.version(), scope,
                input.provider().trim(), input.apiStyle(), input.modelId().trim(), "Connection test", ModelEndpointPolicy.normalize(input.endpoint()),
                input.apiKey() != null ? "transient:" + id : "credential:" + existing.credentialId(), existing == null ? 0 : existing.credentialVersion(),
                profile.capabilityProfile(), input.compatibilityProfile(), new ResolvedModelConfig.EffectiveOptions(MultiProviderProperties.ThinkingMode.DISABLED, null, null, 256),
                32768, 24000, false, allowed);
    }

    private FileModelState.Connection saved(ModelAccessContext scope, ModelManagementRequests.ConnectionTest input) {
        if (!ModelResourceId.belongs(input.connectionId(), 'c', scope)) throw ModelManagementException.missing();
        return files.read(scope, state -> {
            var connection = state.connections.get(input.connectionId());
            if (connection == null) throw ModelManagementException.missing();
            if (input.expectedVersion() == null || input.expectedVersion() < 1 || input.expectedVersion() == Long.MAX_VALUE) throw invalid("VERSION");
            if (input.expectedVersion() != connection.version()) throw ModelManagementException.conflict();
            return connection;
        });
    }
    private void recheck(ModelAccessContext expected, ModelManagementRequests.ConnectionTest input) {
        var current = management.scope(true);
        if (current.userId() != expected.userId() || !StartupModelConfigRepository.sameScope(current, expected)) {
            throw new ModelManagementException("MODEL_MANAGEMENT_FORBIDDEN", HttpStatus.FORBIDDEN);
        }
        if (input.connectionId() != null) saved(current, input);
    }
    private void limit(ModelAccessContext scope) {
        Instant now = Instant.now();
        synchronized (rates) {
            var recent = rates.get(scope.workspaceType() + "/" + scope.ownerId(), ignored -> new ArrayDeque<>());
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minusSeconds(60))) recent.removeFirst();
            if (recent.size() >= 3) throw new ModelManagementException("MODEL_TEST_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS);
            recent.addLast(now);
        }
    }
    private static void validate(ModelManagementRequests.ConnectionTest input) {
        if (input == null) throw ModelManagementException.invalid();
        text(input.provider(), 64, "PROVIDER");text(input.endpoint(), 2048, "ENDPOINT");text(input.modelId(), 256, "MODEL_ID");
        if (ModelProfiles.all().stream().noneMatch(p -> p.apiStyle() == input.apiStyle() && p.compatibilityProfile().equals(input.compatibilityProfile()))) throw invalid("API");
        java.net.URI endpoint;
        try { endpoint = ModelEndpointPolicy.normalize(input.endpoint()); }
        catch (RuntimeException e) { throw invalid("ENDPOINT"); }
        if (input.apiStyle() == ModelApiStyle.DASHSCOPE_CHAT && !endpoint.toString().equals("https://dashscope.aliyuncs.com/api/v1/")) {
            throw new ModelManagementException("MODEL_TEST_DASHSCOPE_ENDPOINT_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (input.apiKey() != null) {
            text(input.apiKey(), 16384, "KEY");
            if (input.apiKey().matches("[＊*]+")) throw invalid("KEY");
        }
        if (input.connectionId() == null && input.expectedVersion() != null) throw invalid("VERSION");
    }
    private static ModelManagementException invalid(String field) { return new ModelManagementException("MODEL_TEST_" + field + "_INVALID", HttpStatus.BAD_REQUEST); }
    private static void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid(field);
    }
    private static String code(Throwable error) {
        String code = "MODEL_TEST_FAILED";
        for (int depth=0; error != null && depth<8; depth++, error=error.getCause()) {
            if (error instanceof ModelIntegrationException e && "MODEL_CONFIG_UNAVAILABLE".equals(e.getReason())) return "MODEL_MANAGEMENT_UNAVAILABLE";
            if (error instanceof ModelIntegrationException e && SAFE_CODES.contains(e.getReason())) return e.getReason();
            if (error instanceof ModelManagementException e && SAFE_CODES.contains(e.getMessage())) return e.getMessage();
            if (error instanceof dev.langchain4j.exception.AuthenticationException) code="MODEL_AUTH_FAILED";
            else if (error instanceof dev.langchain4j.exception.RateLimitException) code="MODEL_RATE_LIMITED";
            else if (error instanceof dev.langchain4j.exception.TimeoutException || error instanceof java.net.SocketTimeoutException) code="MODEL_UPSTREAM_TIMEOUT";
        }
        return code;
    }
    private static ConnectionTest result(boolean success, String code, long started) {
        return new ConnectionTest(success, code, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
