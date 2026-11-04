package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.AiModelCatalog;
import edu.zsc.ai.config.ai.QwenProperties;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import static edu.zsc.ai.config.ai.model.MultiProviderProperties.*;

/** Atomic startup import. No implicit inheritance between users or organizations. */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties({MultiProviderProperties.class, QwenProperties.class})
public class StartupModelConfigRepository implements ModelConfigRepository {
    private final MultiProviderProperties properties;
    private final AiModelCatalog legacyCatalog;
    private final QwenProperties qwen;
    private volatile Map<String, ResolvedModelConfig> models = Map.of();
    private volatile Map<String, String> bindings = Map.of();
    private volatile Map<String, String> aliases = Map.of();

    public boolean enabled() { return properties.getModelRouting().isEnabled(); }
    public int maxCallsPerRun() { return properties.getModelRouting().getMaxCallsPerRun(); }

    @PostConstruct
    public void initialize() {
        if (!enabled()) return;
        if (maxCallsPerRun() < 1 || maxCallsPerRun() > 1024) invalid("max-calls-per-run");
        Map<String, Connection> connections = new LinkedHashMap<>();
        List<Model> definitions = new ArrayList<>(properties.getModelConfigs());
        List<Bindings> purposeBindings = new ArrayList<>(properties.getModelBindings());
        for (Connection connection : properties.getModelConnections()) addConnection(connections, connection);

        for (Bindings scope : properties.getModelRouting().getLegacyWorkspaces()) {
            validateScope(scope.getWorkspaceType(), scope.getOwnerId());
            Connection connection = new Connection();
            connection.setCode("legacy-qwen");
            connection.setWorkspaceType(scope.getWorkspaceType());
            connection.setOwnerId(scope.getOwnerId());
            connection.setProvider("ALIBABA_CLOUD");
            connection.setApiStyle(qwen.getGateway().isEnabled()
                    ? ModelApiStyle.OPENAI_CHAT_COMPLETIONS : ModelApiStyle.DASHSCOPE_CHAT);
            connection.setEndpoint(qwen.getGateway().isEnabled() ? qwen.getGateway().getBaseUrl()
                    : "https://dashscope.aliyuncs.com/api/v1");
            connection.setCredentialRef("deployment:qwen");
            connection.setCompatibilityProfile("qwen-chat-v1");
            addConnection(connections, connection);
            for (var old : legacyCatalog.listSupportedModels()) {
                Model model = new Model();
                model.setWorkspaceType(scope.getWorkspaceType());
                model.setOwnerId(scope.getOwnerId());
                model.setCode(old.getModelName());
                model.setConnectionCode(connection.getCode());
                model.setName(old.getModelName());
                model.setModelId(old.getApiModelName());
                model.setCapabilityProfile("qwen-tools-v1");
                model.setLegacyNames(List.of(old.getModelName()));
                model.setChatVisible(old.isChatVisible());
                model.setMemoryThreshold(old.getMemoryThreshold());
                model.setMaxContextTokens(old.getMaxContextTokens());
                model.getDefaultOptions().setThinkingMode(old.isSupportThinking() ? ThinkingMode.ENABLED : ThinkingMode.DISABLED);
                if (old.isSupportThinking()) model.getDefaultOptions().setThinkingBudget(qwen.getParameters().getThinkingBudget());
                definitions.add(model);
            }
            Bindings imported = new Bindings();
            imported.setWorkspaceType(scope.getWorkspaceType());
            imported.setOwnerId(scope.getOwnerId());
            for (ModelPurpose purpose : ModelPurpose.values()) {
                imported.getPurposes().put(purpose, purpose == ModelPurpose.COMPRESSION
                        ? legacyCatalog.compressionModelName() : legacyCatalog.defaultModelName());
            }
            purposeBindings.add(imported);
        }

        Map<String, ResolvedModelConfig> resolved = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Model model : definitions) {
            validateScope(model.getWorkspaceType(), model.getOwnerId());
            String id = id(model.getWorkspaceType(), model.getOwnerId(), model.getCode());
            Connection connection = connections.get(id(model.getWorkspaceType(), model.getOwnerId(), model.getConnectionCode()));
            if (connection == null) invalid("model connection must belong to the same workspace");
            if (model.getVersion() < 1 || model.getMemoryThreshold() <= 0
                    || model.getMaxContextTokens() <= model.getMemoryThreshold()) invalid("model limits/version");
            require(model.getName()); require(model.getModelId());
            ProviderCompatibilityPolicy.validate(model, connection);
            var endpoint = ModelEndpointPolicy.normalize(connection.getEndpoint());
            Options options = model.getDefaultOptions();
            ResolvedModelConfig config = new ResolvedModelConfig(id, model.getVersion(),
                    id(connection.getWorkspaceType(), connection.getOwnerId(), connection.getCode()), connection.getVersion(),
                    new ModelAccessContext(0, model.getWorkspaceType(), model.getOwnerId()), connection.getProvider(),
                    connection.getApiStyle(), model.getModelId(), model.getName(), endpoint,
                    connection.getCredentialRef(), connection.getCredentialVersion(), model.getCapabilityProfile(),
                    connection.getCompatibilityProfile(), new ResolvedModelConfig.EffectiveOptions(options.getThinkingMode(),
                    options.getThinkingBudget(), options.getTemperature(), ProviderCompatibilityPolicy.effectiveOutputLimit(model, connection), ProviderCompatibilityPolicy.effectiveReasoningEffort(model, connection)),
                    model.getMaxContextTokens(), model.getMemoryThreshold(), model.isChatVisible(),
                    connection.getAllowedPrivateHosts().stream().map(host -> host.toLowerCase(Locale.ROOT))
                            .collect(Collectors.toUnmodifiableSet()));
            // Disabled definitions still reserve IDs, preventing accidental shadowing.
            if (resolved.putIfAbsent(id, config) != null) invalid("duplicate model code");
            if (!model.isEnabled() || !connection.isEnabled()) continue;
            for (String alias : model.getLegacyNames()) {
                require(alias);
                if (names.putIfAbsent(scope(config.access()) + alias, id) != null) invalid("ambiguous legacy model name");
            }
        }
        Set<String> disabled = new HashSet<>();
        for (Model model : definitions) {
            Connection connection = connections.get(id(model.getWorkspaceType(), model.getOwnerId(), model.getConnectionCode()));
            if (!model.isEnabled() || !connection.isEnabled()) disabled.add(id(model.getWorkspaceType(), model.getOwnerId(), model.getCode()));
        }
        disabled.forEach(resolved::remove);
        Map<String, String> bound = new LinkedHashMap<>();
        for (Bindings binding : purposeBindings) {
            validateScope(binding.getWorkspaceType(), binding.getOwnerId());
            for (var entry : binding.getPurposes().entrySet()) {
                String target = id(binding.getWorkspaceType(), binding.getOwnerId(), entry.getValue());
                if (!resolved.containsKey(target)) invalid("binding references unavailable model");
                String key = scope(new ModelAccessContext(0, binding.getWorkspaceType(), binding.getOwnerId())) + entry.getKey();
                if (bound.putIfAbsent(key, target) != null) invalid("duplicate purpose binding");
            }
        }
        models = Collections.unmodifiableMap(resolved);
        aliases = Map.copyOf(names);
        bindings = Map.copyOf(bound);
    }

    public List<ResolvedModelConfig> list(ModelAccessContext access) {
        return models.values().stream().filter(c -> sameScope(c.access(), access)).toList();
    }
    public ResolvedModelConfig find(String id) { return models.get(id); }
    public String binding(ModelAccessContext access, ModelPurpose purpose) { return bindings.get(scope(access) + purpose); }
    public String alias(ModelAccessContext access, String name) { return aliases.get(scope(access) + name); }
    public List<ResolvedModelConfig> exportModels() { return List.copyOf(models.values()); }
    public Map<String, String> exportAliases() { return aliases; }
    public Map<String, String> exportBindings() { return bindings; }
    public static boolean sameScope(ModelAccessContext a, ModelAccessContext b) {
        return a.workspaceType() == b.workspaceType() && a.ownerId() == b.ownerId();
    }
    private static String scope(ModelAccessContext access) { return "startup/" + access.workspaceType() + "/" + access.ownerId() + "/"; }
    private static String id(WorkspaceTypeEnum type, Long owner, String code) {
        require(code);
        if (!code.matches("[A-Za-z0-9._-]+")) invalid("invalid code");
        return scope(new ModelAccessContext(0, type, owner)) + code;
    }
    private static void addConnection(Map<String, Connection> connections, Connection c) {
        validateScope(c.getWorkspaceType(), c.getOwnerId()); require(c.getProvider()); require(c.getCredentialRef());
        if (!"deployment:qwen".equals(c.getCredentialRef()) && !c.getCredentialRef().matches("env:[A-Z][A-Z0-9_]*")) invalid("unsupported credential reference");
        if (c.getVersion() < 1 || c.getCredentialVersion() < 1 || !"API_KEY".equals(c.getAuthType())) invalid("connection auth/version");
        if (connections.putIfAbsent(id(c.getWorkspaceType(), c.getOwnerId(), c.getCode()), c) != null) invalid("duplicate connection code");
    }
    private static void validateScope(WorkspaceTypeEnum type, Long owner) {
        if (type == null || owner == null || owner <= 0) invalid("explicit workspace-type and owner-id are required");
    }
    private static void require(String value) { if (value == null || value.isBlank()) invalid("required value missing"); }
    private static void invalid(String reason) { throw new IllegalStateException("Invalid AI model configuration: " + reason); }
}
