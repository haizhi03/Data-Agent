package edu.zsc.ai.domain.service.agent;

import edu.zsc.ai.api.model.request.ErQueryFocus;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ErDiagramResponse;
import edu.zsc.ai.domain.service.db.ErDiagramService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Verifies circled tables against metadata, trims nothing itself, and resolves
 * foreign keys inside the selection plus one hop outward.
 */
@Service
@RequiredArgsConstructor
public class ErQueryFocusResolver {

    private final ErDiagramService erDiagramService;

    public ErQueryFocus resolve(ErQueryFocus focus) {
        if (focus == null || focus.getTables() == null || focus.getTables().isEmpty() || focus.getConnectionId() == null) {
            return focus;
        }
        Map<String, ErQueryFocus.Table> verified = new LinkedHashMap<>();
        List<ErQueryFocus.Relation> seenRelations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();
        for (Map.Entry<Scope, List<String>> group : groups(focus).entrySet()) {
            ErDiagramResponse diagram = erDiagramService.load(
                    new DbContext(focus.getConnectionId(), group.getKey().catalog(), group.getKey().schema()),
                    false,
                    group.getValue());
            if (diagram.getTables() != null) {
                for (ErDiagramResponse.ErTable table : diagram.getTables()) {
                    if (table == null || table.isExternal() || StringUtils.isBlank(table.getName())) {
                        continue;
                    }
                    if (!requested(focus, table.getCatalog(), table.getSchema(), table.getName())) {
                        continue;
                    }
                    verified.putIfAbsent(key(table.getCatalog(), table.getSchema(), table.getName()), copyTable(table));
                }
            }
            if (diagram.getRelations() == null) {
                continue;
            }
            for (ErDiagramResponse.ErRelation relation : diagram.getRelations()) {
                ErQueryFocus.Relation copied = copyRelation(relation);
                if (copied == null || !relationKeys.add(relationKey(copied))) {
                    continue;
                }
                seenRelations.add(copied);
            }
        }

        List<ErQueryFocus.Table> ordered = new ArrayList<>();
        Set<String> selected = new LinkedHashSet<>();
        for (ErQueryFocus.Table requested : focus.getTables()) {
            if (requested == null || StringUtils.isBlank(requested.getName())) {
                continue;
            }
            String catalog = firstNonBlank(requested.getCatalog(), focus.getCatalogName());
            String schema = firstNonBlank(requested.getSchema(), focus.getSchemaName());
            ErQueryFocus.Table found = verified.get(key(catalog, schema, requested.getName()));
            if (found == null) {
                continue;
            }
            String id = key(found.getCatalog(), found.getSchema(), found.getName());
            if (selected.add(id)) {
                ordered.add(found);
            }
        }

        List<ErQueryFocus.Relation> inside = new ArrayList<>();
        List<ErQueryFocus.Relation> outside = new ArrayList<>();
        for (ErQueryFocus.Relation relation : seenRelations) {
            boolean fromIn = selected.contains(key(relation.getFromCatalog(), relation.getFromSchema(), relation.getFromTable()));
            boolean toIn = selected.contains(key(relation.getToCatalog(), relation.getToSchema(), relation.getToTable()));
            if (fromIn && toIn) {
                inside.add(relation);
            } else if (fromIn || toIn) {
                outside.add(relation);
            }
        }

        ErQueryFocus resolved = new ErQueryFocus();
        resolved.setConnectionId(focus.getConnectionId());
        resolved.setConnectionName(focus.getConnectionName());
        resolved.setCatalogName(focus.getCatalogName());
        resolved.setSchemaName(focus.getSchemaName());
        resolved.setTables(ordered);
        resolved.setInsideRelations(inside);
        resolved.setOutsideRelations(outside);
        return resolved;
    }

    private static Map<Scope, List<String>> groups(ErQueryFocus focus) {
        Map<Scope, List<String>> groups = new LinkedHashMap<>();
        for (ErQueryFocus.Table table : focus.getTables()) {
            if (table == null || StringUtils.isBlank(table.getName())) {
                continue;
            }
            Scope scope = new Scope(
                    firstNonBlank(table.getCatalog(), focus.getCatalogName()),
                    firstNonBlank(table.getSchema(), focus.getSchemaName()));
            groups.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(table.getName());
        }
        return groups;
    }

    private static boolean requested(ErQueryFocus focus, String catalog, String schema, String name) {
        for (ErQueryFocus.Table table : focus.getTables()) {
            if (table == null || StringUtils.isBlank(table.getName())) {
                continue;
            }
            String wantedCatalog = firstNonBlank(table.getCatalog(), focus.getCatalogName());
            String wantedSchema = firstNonBlank(table.getSchema(), focus.getSchemaName());
            if (key(wantedCatalog, wantedSchema, table.getName()).equals(key(catalog, schema, name))) {
                return true;
            }
        }
        return false;
    }

    private static ErQueryFocus.Table copyTable(ErDiagramResponse.ErTable source) {
        ErQueryFocus.Table table = new ErQueryFocus.Table();
        table.setName(source.getName());
        table.setCatalog(source.getCatalog());
        table.setSchema(source.getSchema());
        List<ErQueryFocus.Column> columns = new ArrayList<>();
        if (source.getColumns() != null) {
            for (ErDiagramResponse.ErColumn column : source.getColumns()) {
                if (column == null || StringUtils.isBlank(column.getName())) {
                    continue;
                }
                ErQueryFocus.Column copied = new ErQueryFocus.Column();
                copied.setName(column.getName());
                copied.setTypeName(column.getTypeName());
                copied.setPrimaryKey(column.isPrimaryKey());
                columns.add(copied);
            }
        }
        table.setColumns(columns);
        return table;
    }

    private static ErQueryFocus.Relation copyRelation(ErDiagramResponse.ErRelation source) {
        if (source == null || StringUtils.isBlank(source.getFromTable()) || StringUtils.isBlank(source.getToTable())) {
            return null;
        }
        ErQueryFocus.Relation relation = new ErQueryFocus.Relation();
        relation.setFromCatalog(source.getFromCatalog());
        relation.setFromSchema(source.getFromSchema());
        relation.setFromTable(source.getFromTable());
        relation.setFromColumns(columns(source.getFromColumns(), source.getFromColumn()));
        relation.setToCatalog(source.getToCatalog());
        relation.setToSchema(source.getToSchema());
        relation.setToTable(source.getToTable());
        relation.setToColumns(columns(source.getToColumns(), source.getToColumn()));
        relation.setCardinality(source.getCardinality());
        relation.setViaCatalog(source.getViaCatalog());
        relation.setViaSchema(source.getViaSchema());
        relation.setViaTable(source.getViaTable());
        return relation;
    }

    private static List<String> columns(List<String> columns, String first) {
        if (columns != null && !columns.isEmpty()) {
            return columns;
        }
        if (StringUtils.isBlank(first)) {
            return List.of();
        }
        return List.of(first);
    }

    private static String relationKey(ErQueryFocus.Relation relation) {
        return key(relation.getFromCatalog(), relation.getFromSchema(), relation.getFromTable())
                + "|" + String.join(",", relation.getFromColumns())
                + "->" + key(relation.getToCatalog(), relation.getToSchema(), relation.getToTable())
                + "|" + String.join(",", relation.getToColumns())
                + "|" + key(relation.getViaCatalog(), relation.getViaSchema(), relation.getViaTable());
    }

    private static String key(String catalog, String schema, String table) {
        return norm(catalog) + "\n" + norm(schema) + "\n" + norm(table);
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return StringUtils.firstNonBlank(preferred, fallback);
    }

    private record Scope(String catalog, String schema) {
    }
}
