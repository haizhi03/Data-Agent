package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.plugin.capability.SqlCompleter;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import edu.zsc.ai.plugin.model.metadata.FunctionMetadata;
import edu.zsc.ai.plugin.model.metadata.ProcedureMetadata;
import edu.zsc.ai.plugin.model.sql.CompletionRoutine;
import edu.zsc.ai.plugin.model.sql.CompletionSynonym;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.util.*;
import java.util.function.Supplier;

/** Account-visible metadata and synonym resolution, memoized for one completion request. */
@Slf4j
final class SqlCompletionMetadataResolver {
    private record MetadataKey(DbContext source, String kind, String object) {}
    private record ObjectKey(DbContext source, String name) {}

    private final String plugin;
    private final Object session;
    private final Connection connection;
    private final SqlCompleter dialect;
    private final SqlCompletionMetadataCache cache;
    private final DefaultPluginManager managers = DefaultPluginManager.getInstance();
    private final Map<MetadataKey, List<?>> loaded = new HashMap<>();
    private final Map<DbContext, Set<String>> objects = new HashMap<>();
    private final Map<ObjectKey, Optional<CompletionSynonym>> targets = new HashMap<>();

    SqlCompletionMetadataResolver(String plugin, Object session, Connection connection,
                                  SqlCompleter dialect, SqlCompletionMetadataCache cache) {
        this.plugin = plugin;
        this.session = session;
        this.connection = connection;
        this.dialect = dialect;
        this.cache = cache;
    }

    @SuppressWarnings("unchecked") // Each metadata kind has one list element type.
    <T> List<T> cached(DbContext source, String kind, String object, Supplier<List<T>> loader) {
        MetadataKey key = new MetadataKey(source, kind, object);
        if (!loaded.containsKey(key)) {
            List<T> values;
            try {
                values = cache.get(plugin, source, kind, object, session, loader);
            } catch (RuntimeException e) {
                log.debug("Completion metadata unavailable for {}: {}", kind, e.getMessage());
                values = List.of();
            }
            loaded.put(key, values);
        }
        return (List<T>) loaded.get(key);
    }

    private DbContext schema(DbContext source, String owner) {
        return new DbContext(source.connectionId(), source.catalog(), owner);
    }

    List<String> packages(DbContext source) {
        return cached(source, "packages", null, () -> dialect.completionPackages(connection, source.schema()));
    }

    List<CompletionRoutine> packageMembers(DbContext source, String packageName) {
        return cached(source, "package-members", packageName,
                () -> dialect.completionPackageMembers(connection, source.schema(), packageName));
    }

    List<FunctionMetadata> functions(DbContext source) {
        return cached(source, "functions", null, () -> managers.getFunctionManagerByPluginId(plugin)
                .getFunctions(connection, source.catalog(), source.schema()));
    }

    List<ProcedureMetadata> procedures(DbContext source) {
        return cached(source, "procedures", null, () -> managers.getProcedureManagerByPluginId(plugin)
                .getProcedures(connection, source.catalog(), source.schema()));
    }

    /** Table comment, or null when absent/unsupported. */
    String tableComment(DbContext source, String table) {
        List<String> values = cached(source, "table-comment", table, () -> {
            String comment = managers.getTableManagerByPluginId(plugin)
                    .getTableComment(connection, source.catalog(), source.schema(), table);
            return comment == null || comment.isBlank() ? List.of() : List.of(comment);
        });
        return values.isEmpty() ? null : values.get(0);
    }

    /** Generated table DDL, or null when unavailable (unsupported, no privilege or not a table). */
    String tableDdl(DbContext source, String table) {
        List<String> values = cached(source, "table-ddl", table, () -> {
            String ddl = managers.getTableManagerByPluginId(plugin)
                    .getTableDdl(connection, source.catalog(), source.schema(), table);
            return ddl == null || ddl.isBlank() ? List.of() : List.of(ddl);
        });
        return values.isEmpty() ? null : values.get(0);
    }

    /** "TABLE" or "VIEW" when the exact-cased name exists in the schema, otherwise null. */
    String objectKind(DbContext source, String name) {
        if (cached(source, "tables", null, () -> managers.getTableManagerByPluginId(plugin)
                .getTableNames(connection, source.catalog(), source.schema())).contains(name)) return "TABLE";
        if (cached(source, "views", null, () -> managers.getViewManagerByPluginId(plugin)
                .getViews(connection, source.catalog(), source.schema())).contains(name)) return "VIEW";
        return null;
    }

    private List<CompletionSynonym> synonyms(DbContext source) {
        return cached(source, "synonyms", null, () -> dialect.completionSynonyms(connection, source.schema()));
    }

    private Set<String> objectNames(DbContext source) {
        return objects.computeIfAbsent(source, db -> {
            Set<String> names = new HashSet<>(packages(db));
            names.addAll(cached(db, "tables", null, () -> managers.getTableManagerByPluginId(plugin)
                    .getTableNames(connection, db.catalog(), db.schema())));
            names.addAll(cached(db, "views", null, () -> managers.getViewManagerByPluginId(plugin)
                    .getViews(connection, db.catalog(), db.schema())));
            return names;
        });
    }

    static boolean accepts(CompletionSynonym synonym, Set<String> types) {
        return synonym.targetType().isBlank() || synonym.targetType().equals("SYNONYM")
                || types.contains(synonym.targetType());
    }

    List<CompletionSynonym> visibleSynonyms(DbContext source, Set<String> types, String prefix, boolean quoted) {
        List<CompletionSynonym> sorted = new ArrayList<>(synonyms(source));
        sorted.sort(Comparator.comparing(s -> !Objects.equals(s.owner(), source.schema())));
        Map<String, CompletionSynonym> visible = new LinkedHashMap<>();
        sorted.forEach(s -> visible.putIfAbsent(s.name(), s)); // Private aliases shadow PUBLIC, regardless of type.
        List<CompletionSynonym> matching = visible.values().stream().filter(s -> accepts(s, types))
                .filter(s -> quoted ? s.name().startsWith(prefix) : s.name().regionMatches(true, 0, prefix, 0, prefix.length()))
                .toList();
        if (matching.isEmpty()) return List.of();
        Set<String> realObjects = objectNames(source);
        return matching.stream().filter(s -> !realObjects.contains(s.name())).toList();
    }

    private CompletionSynonym synonym(DbContext source, String name) {
        List<CompletionSynonym> matching = synonyms(source).stream().filter(s -> s.name().equals(name)).toList();
        if (matching.isEmpty() || objectNames(source).contains(name)) return null;
        return matching.stream().filter(s -> Objects.equals(source.schema(), s.owner())).findFirst()
                .orElseGet(() -> matching.stream().filter(s -> s.owner().equals("PUBLIC")).findFirst().orElse(null));
    }

    CompletionSynonym localTarget(DbContext source, String alias) {
        ObjectKey key = new ObjectKey(source, alias);
        if (!targets.containsKey(key)) targets.put(key,
                Optional.ofNullable(localTarget(source, alias, new HashSet<>(), 0)));
        return targets.get(key).orElse(null);
    }

    private CompletionSynonym localTarget(DbContext source, String alias, Set<ObjectKey> visiting, int depth) {
        ObjectKey key = new ObjectKey(source, alias);
        if (depth > 16 || !visiting.add(key)) return null;
        CompletionSynonym synonym = synonym(source, alias);
        CompletionSynonym target = synonym;
        if (synonym != null) {
            if (!synonym.databaseLink().isBlank()) target = null;
            else if (synonym.targetType().isBlank() || synonym.targetType().equals("SYNONYM")) {
                DbContext owner = schema(source, synonym.targetSchema());
                if (synonym(owner, synonym.targetName()) != null)
                    target = localTarget(owner, synonym.targetName(), visiting, depth + 1);
            }
        }
        return target;
    }

    List<ColumnMetadata> columns(DbContext source, String table) {
        return columns(source, table, new HashSet<>(), 0);
    }

    private List<ColumnMetadata> columns(DbContext source, String table, Set<ObjectKey> visiting, int depth) {
        if (depth > 16 || !visiting.add(new ObjectKey(source, table))) return List.of();
        List<ColumnMetadata> fields = cached(source, "columns", table, () -> managers.getColumnManagerByPluginId(plugin)
                .getColumns(connection, source.catalog(), source.schema(), table));
        if (!fields.isEmpty()) return fields;
        CompletionSynonym synonym = synonym(source, table);
        if (synonym == null || !synonym.databaseLink().isBlank()) return List.of();
        return columns(schema(source, synonym.targetSchema()), synonym.targetName(), visiting, depth + 1);
    }
}
