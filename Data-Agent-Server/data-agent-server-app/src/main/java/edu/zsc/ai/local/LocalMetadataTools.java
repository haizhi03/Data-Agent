package edu.zsc.ai.local;

import edu.zsc.ai.api.model.request.ChatDefaultTarget;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.agent.ChatReferenceService;
import edu.zsc.ai.domain.service.db.ColumnService;
import edu.zsc.ai.domain.service.db.TableService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded metadata reads shared by the Java ReAct tools and the Pi worker bridge.
 * Scope resolution re-validates every wire-supplied connection/catalog/schema against
 * local connection access; nothing arriving over the tool channel is trusted directly.
 * Results always carry the resolved target identity so callers can verify what was read.
 */
final class LocalMetadataTools {
    static final int MAX_TABLES = 200;
    static final int MAX_COLUMNS = 100;

    private final LocalConnectionService connections;
    private final ChatReferenceService references;
    private final TableService tables;
    private final ColumnService columns;

    LocalMetadataTools(LocalConnectionService connections, ChatReferenceService references,
            TableService tables, ColumnService columns) {
        this.connections = connections; this.references = references; this.tables = tables; this.columns = columns;
    }

    /** Filled but not yet validated wire values; used to scope the caller context before access checks. */
    record Probe(Long connectionId, String catalog, String schema) { }

    /** A resolved tool scope: validated identity plus the connection version pinned for this run. */
    record Scope(DbContext target, long expectedVersion) { }

    /**
     * Fill unspecified fields from the conversation default (never over explicit values).
     * Pure: no access checks, so callers can establish a request context first.
     */
    static Probe fillDefaults(Long connectionId, String catalogName, String schemaName, DbContext defaultTarget) {
        Long id = connectionId == null ? defaultTarget.connectionId() : connectionId;
        boolean same = Objects.equals(id, defaultTarget.connectionId());
        String catalog = catalogName == null && same ? defaultTarget.catalog() : catalogName;
        String schema = schemaName == null && same && Objects.equals(catalog, defaultTarget.catalog())
                ? defaultTarget.schema() : schemaName;
        return new Probe(id, catalog, schema);
    }

    /**
     * Validate access, normalize through the plugin capability rules and pin the connection
     * version for this run. Requires an established request context. Throws on any violation;
     * wire-supplied identities are never trusted directly.
     */
    Scope resolveValidated(Probe probe, Map<Long, Long> versions) {
        if (probe.connectionId() == null) throw LocalRequestException.forbidden();
        connections.assertReadable(probe.connectionId());
        var normalized = references.normalizeTarget(
                new ChatDefaultTarget(probe.connectionId(), probe.catalog(), probe.schema()));
        var resolved = new DbContext(normalized.getConnectionId(), normalized.getCatalogName(), normalized.getSchemaName());
        long expected = versions.computeIfAbsent(probe.connectionId(), key -> connections.get(key).version());
        if (connections.get(probe.connectionId()).version() != expected) {
            throw new LocalRequestException(409, "CONNECTION_REVISION_CONFLICT", "连接已修改，请重新设置默认目标");
        }
        return new Scope(resolved, expected);
    }

    /** Execute the read while the pinned connection version is asserted around the actual metadata call. */
    <T> T withPinnedVersion(Scope scope, java.util.function.Supplier<T> action) {
        return connections.withExpectedVersion(scope.target().connectionId(), scope.expectedVersion(), action);
    }

    Map<String, Object> listTables(DbContext target) {
        var result = tables.getTables(target);
        var body = new LinkedHashMap<String, Object>();
        body.put("target", identity(target));
        body.put("tables", result.stream().limit(MAX_TABLES).toList());
        body.put("truncated", result.size() > MAX_TABLES);
        return body;
    }

    Map<String, Object> describeTable(DbContext target, String tableName) {
        if (tableName == null || !tableName.matches("[\\p{L}\\p{N}_$#]{1,128}")) throw LocalRequestException.invalid();
        var result = columns.listColumns(target, tableName);
        var body = new LinkedHashMap<String, Object>();
        body.put("target", identity(target));
        body.put("table", tableName);
        body.put("columns", result.stream().limit(MAX_COLUMNS).toList());
        body.put("truncated", result.size() > MAX_COLUMNS);
        return body;
    }

    static Map<String, Object> identity(DbContext target) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("connectionId", target.connectionId());
        identity.put("catalogName", target.catalog());
        identity.put("schemaName", target.schema());
        return identity;
    }
}
