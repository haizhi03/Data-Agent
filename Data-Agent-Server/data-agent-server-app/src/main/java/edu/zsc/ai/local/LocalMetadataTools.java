package edu.zsc.ai.local;

import edu.zsc.ai.api.model.request.ChatDefaultTarget;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.agent.ChatReferenceService;
import edu.zsc.ai.domain.service.db.ColumnService;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.domain.service.db.DatabaseService;
import edu.zsc.ai.domain.service.db.SchemaService;
import edu.zsc.ai.plugin.SqlPlugin;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
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
    private final DatabaseService databases;
    private final SchemaService schemas;

    LocalMetadataTools(LocalConnectionService connections, ChatReferenceService references,
            TableService tables, ColumnService columns, DatabaseService databases, SchemaService schemas) {
        this.connections = connections; this.references = references; this.tables = tables; this.columns = columns;
        this.databases = databases; this.schemas = schemas;
    }

    /** Discovery exposes identities only; no addresses, users, driver paths, properties or credentials. */
    Map<String, Object> listConnections(Map<Long, Long> versions) {
        var available = connections.list();
        var items = available.stream().limit(50).map(connection -> {
            versions.putIfAbsent(connection.id(), connection.version());
            return Map.of("connectionId", (Object) connection.id(), "name", connection.name(), "dbType", connection.dbType());
        }).toList();
        return Map.of("connections", items, "total", available.size(), "truncated", available.size() > items.size());
    }

    Map<String, Object> listNamespaces(DbContext target) {
        var connection = connections.get(target.connectionId());
        var plugin = DefaultPluginManager.getInstance().getPluginByDbTypeAndVersion(connection.dbType(), null);
        boolean supportsCatalog = plugin instanceof SqlPlugin sql && sql.supportDatabase();
        boolean supportsSchema = plugin instanceof SqlPlugin sql && sql.supportSchema();
        // Discover one level at a time: never open every connection or fan out over every catalog.
        var catalogs = supportsCatalog && target.catalog() == null ? databases.getDatabases(target.connectionId()) : java.util.List.<String>of();
        var schemaNames = supportsSchema && (!supportsCatalog || target.catalog() != null)
                ? schemas.listSchemas(target.connectionId(), target.catalog()) : java.util.List.<String>of();
        var body = new LinkedHashMap<String, Object>();
        body.put("target", identity(target));
        body.put("supportsCatalog", supportsCatalog); body.put("supportsSchema", supportsSchema);
        body.put("catalogs", catalogs.stream().limit(200).toList());
        body.put("schemas", schemaNames.stream().limit(200).toList());
        body.put("truncated", catalogs.size() > 200 || schemaNames.size() > 200);
        return body;
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
        requireNamespace(target);
        var result = tables.getTables(target);
        var body = new LinkedHashMap<String, Object>();
        body.put("target", identity(target));
        body.put("tables", result.stream().limit(MAX_TABLES).toList());
        body.put("truncated", result.size() > MAX_TABLES);
        return body;
    }

    Map<String, Object> describeTable(DbContext target, String tableName) {
        if (tableName == null || !tableName.matches("[\\p{L}\\p{N}_$#]{1,128}")) throw LocalRequestException.invalid();
        requireNamespace(target);
        var result = columns.listColumns(target, tableName);
        var body = new LinkedHashMap<String, Object>();
        body.put("target", identity(target));
        body.put("table", tableName);
        body.put("columns", result.stream().limit(MAX_COLUMNS).toList());
        body.put("truncated", result.size() > MAX_COLUMNS);
        return body;
    }

    private void requireNamespace(DbContext target) {
        var connection = connections.get(target.connectionId());
        var plugin = DefaultPluginManager.getInstance().getPluginByDbTypeAndVersion(connection.dbType(), null);
        if (plugin instanceof SqlPlugin sql && ((sql.supportDatabase() && target.catalog() == null)
                || (sql.supportSchema() && target.schema() == null))) {
            throw new LocalRequestException(400,"LOCAL_NAMESPACE_REQUIRED","请先调用 list_namespaces 发现数据库或 Schema，再使用完整目标读取表");
        }
    }

    static Map<String, Object> identity(DbContext target) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("connectionId", target.connectionId());
        identity.put("catalogName", target.catalog());
        identity.put("schemaName", target.schema());
        return identity;
    }
}
