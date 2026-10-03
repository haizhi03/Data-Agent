package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ErDiagramResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.ErDiagramService;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.capability.ConstraintManager;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.constant.JdbcMetaDataConstants;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ErDiagramServiceImpl implements ErDiagramService {

    private static final int TABLE_LIMIT = 80;

    private final ConnectionService connectionService;

    @Override
    public ErDiagramResponse load(DbContext db) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager tables = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        ColumnManager columns = DefaultPluginManager.getInstance().getColumnManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();
            List<String> tableNames = tables.getTableNames(connection, db.catalog(), db.schema());
            boolean truncated = tableNames.size() > TABLE_LIMIT;
            if (truncated) {
                tableNames = tableNames.subList(0, TABLE_LIMIT);
            }

            String scopeCatalog = blankToNull(db.catalog());
            String scopeSchema = blankToNull(db.schema());
            Map<String, String> comments = loadTableComments(connection, db.catalog(), db.schema());
            Map<String, String> canonicalNames = new LinkedHashMap<>();
            List<ErDiagramResponse.ErTable> erTables = new ArrayList<>();
            for (String tableName : tableNames) {
                if (StringUtils.isBlank(tableName)) {
                    continue;
                }
                canonicalNames.put(tableName.toLowerCase(Locale.ROOT), tableName);
                erTables.add(ErDiagramResponse.ErTable.builder()
                        .name(tableName)
                        .catalog(scopeCatalog)
                        .schema(scopeSchema)
                        .external(false)
                        .comment(comments.getOrDefault(tableName.toLowerCase(Locale.ROOT), ""))
                        .columns(toColumns(columns.getColumns(connection, db.catalog(), db.schema(), tableName)))
                        .build());
            }

            boolean schemaScoped = DefaultPluginManager.getInstance().supportsSchemaByPluginId(active.pluginId());
            ErDiagramGraph.Scope scope = new ErDiagramGraph.Scope(scopeCatalog, scopeSchema, schemaScoped);
            List<ErDiagramGraph.KeyRow> keys = new ArrayList<>();
            DatabaseMetaData metaData = connection.getMetaData();
            for (String tableName : tableNames) {
                if (StringUtils.isBlank(tableName)) {
                    continue;
                }
                appendJdbcKeys(keys, metaData, db.catalog(), db.schema(), tableName);
            }
            appendConstraintKeys(keys, connection, active.pluginId(), db.catalog(), db.schema(), schemaScoped, tableNames);

            ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(scope, canonicalNames, keys);
            for (ErDiagramGraph.Endpoint endpoint : graph.externalTables()) {
                erTables.add(loadExternalTable(columns, connection, endpoint, graph.relations()));
            }
            return ErDiagramResponse.builder()
                    .tables(erTables)
                    .relations(toRelations(graph.relations()))
                    .truncated(truncated)
                    .externalTruncated(graph.externalTruncated())
                    .build();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load ER diagram: " + e.getMessage(), e);
        }
    }

    private List<ErDiagramResponse.ErColumn> toColumns(List<ColumnMetadata> columns) {
        List<ErDiagramResponse.ErColumn> result = new ArrayList<>();
        if (columns == null) {
            return result;
        }
        for (ColumnMetadata column : columns) {
            result.add(ErDiagramResponse.ErColumn.builder()
                    .name(column.name())
                    .typeName(formatType(column))
                    .comment(column.remarks() == null ? "" : column.remarks())
                    .primaryKey(column.isPrimaryKeyPart())
                    .build());
        }
        return result;
    }

    private String formatType(ColumnMetadata column) {
        String typeName = column.typeName() == null ? "" : column.typeName();
        String upper = typeName.toUpperCase(Locale.ROOT);
        int size = column.columnSize();
        if (size <= 0) {
            return typeName;
        }
        boolean sized = upper.contains("CHAR") || upper.contains("BINARY")
                || upper.equals("DECIMAL") || upper.equals("NUMERIC") || upper.equals("NUMBER")
                || upper.equals("DEC");
        if (!sized) {
            return typeName;
        }
        if (column.decimalDigits() > 0 && (upper.equals("DECIMAL") || upper.equals("NUMERIC")
                || upper.equals("NUMBER") || upper.equals("DEC"))) {
            return typeName + "(" + size + "," + column.decimalDigits() + ")";
        }
        return typeName + "(" + size + ")";
    }

    private Map<String, String> loadTableComments(Connection connection, String catalog, String schema) {
        Map<String, String> comments = new LinkedHashMap<>();
        try (ResultSet rs = connection.getMetaData().getTables(
                catalog, schema, null, new String[]{JdbcMetaDataConstants.TABLE_TYPE_TABLE})) {
            while (rs.next()) {
                String name = rs.getString(JdbcMetaDataConstants.TABLE_NAME);
                String remark = rs.getString(JdbcMetaDataConstants.REMARKS);
                if (StringUtils.isNotBlank(name) && StringUtils.isNotBlank(remark)) {
                    comments.put(name.toLowerCase(Locale.ROOT), remark);
                }
            }
        } catch (SQLException e) {
            log.debug("Table comments are unavailable: {}", e.getMessage());
        }
        return comments;
    }

    private void appendJdbcKeys(List<ErDiagramGraph.KeyRow> keys, DatabaseMetaData metaData,
                                String catalog, String schema, String tableName) {
        try (ResultSet rs = metaData.getImportedKeys(catalog, schema, tableName)) {
            readKeyRows(keys, rs);
        } catch (SQLException e) {
            log.debug("Imported keys unavailable for {}: {}", tableName, e.getMessage());
        }
        try (ResultSet rs = metaData.getExportedKeys(catalog, schema, tableName)) {
            readKeyRows(keys, rs);
        } catch (SQLException e) {
            log.debug("Exported keys unavailable for {}: {}", tableName, e.getMessage());
        }
    }

    private void readKeyRows(List<ErDiagramGraph.KeyRow> keys, ResultSet rs) throws SQLException {
        while (rs.next()) {
            keys.add(new ErDiagramGraph.KeyRow(
                    rs.getString(JdbcMetaDataConstants.PKTABLE_CAT),
                    rs.getString(JdbcMetaDataConstants.PKTABLE_SCHEM),
                    rs.getString(JdbcMetaDataConstants.PKTABLE_NAME),
                    rs.getString(JdbcMetaDataConstants.PKCOLUMN_NAME),
                    rs.getString(JdbcMetaDataConstants.FKTABLE_CAT),
                    rs.getString(JdbcMetaDataConstants.FKTABLE_SCHEM),
                    rs.getString(JdbcMetaDataConstants.FKTABLE_NAME),
                    rs.getString(JdbcMetaDataConstants.FKCOLUMN_NAME),
                    rs.getString(JdbcMetaDataConstants.FK_NAME)));
        }
    }

    /**
     * Dictionary constraints cover outbound foreign keys when the JDBC driver
     * omits the referenced schema. Inbound keys still come from getExportedKeys.
     */
    private void appendConstraintKeys(List<ErDiagramGraph.KeyRow> keys, Connection connection, String pluginId,
                                      String catalog, String schema, boolean schemaScoped, List<String> tableNames) {
        ConstraintManager manager;
        try {
            manager = DefaultPluginManager.getInstance().getConstraintManagerByPluginId(pluginId);
        } catch (RuntimeException e) {
            return;
        }
        for (String tableName : tableNames) {
            if (StringUtils.isBlank(tableName)) {
                continue;
            }
            List<ConstraintMetadata> constraints;
            try {
                constraints = manager.getConstraints(connection, catalog, schema, tableName);
            } catch (RuntimeException e) {
                log.debug("Constraints unavailable for {}: {}", tableName, e.getMessage());
                continue;
            }
            if (constraints == null) {
                continue;
            }
            keys.addAll(ErDiagramGraph.keysFromConstraints(catalog, schema, tableName, schemaScoped, constraints));
        }
    }

    private List<ErDiagramResponse.ErRelation> toRelations(List<ErDiagramGraph.Relation> relations) {
        List<ErDiagramResponse.ErRelation> result = new ArrayList<>();
        for (ErDiagramGraph.Relation relation : relations) {
            result.add(ErDiagramResponse.ErRelation.builder()
                    .name(relation.name())
                    .fromCatalog(relation.from().catalog())
                    .fromSchema(relation.from().schema())
                    .fromTable(relation.from().table())
                    .fromColumn(relation.fromColumn())
                    .toCatalog(relation.to().catalog())
                    .toSchema(relation.to().schema())
                    .toTable(relation.to().table())
                    .toColumn(relation.toColumn())
                    .build());
        }
        return result;
    }

    private ErDiagramResponse.ErTable loadExternalTable(ColumnManager columns, Connection connection,
                                                        ErDiagramGraph.Endpoint endpoint,
                                                        List<ErDiagramGraph.Relation> relations) {
        List<ErDiagramResponse.ErColumn> loaded = List.of();
        try {
            loaded = toColumns(columns.getColumns(connection, endpoint.catalog(), endpoint.schema(), endpoint.table()));
        } catch (RuntimeException e) {
            log.debug("Columns unavailable for external table {}.{}: {}",
                    endpoint.schema() != null ? endpoint.schema() : endpoint.catalog(), endpoint.table(), e.getMessage());
        }
        return ErDiagramResponse.ErTable.builder()
                .name(endpoint.table())
                .catalog(endpoint.catalog())
                .schema(endpoint.schema())
                .external(true)
                .comment("")
                .columns(mergeColumns(loaded, stubColumns(endpoint, relations)))
                .build();
    }

    private List<ErDiagramResponse.ErColumn> stubColumns(ErDiagramGraph.Endpoint table, List<ErDiagramGraph.Relation> relations) {
        List<ErDiagramResponse.ErColumn> columns = new ArrayList<>();
        for (ErDiagramGraph.StubColumn stub : ErDiagramGraph.stubColumns(table, relations)) {
            columns.add(ErDiagramResponse.ErColumn.builder()
                    .name(stub.name())
                    .typeName("")
                    .comment("")
                    .primaryKey(stub.primaryKey())
                    .build());
        }
        return columns;
    }

    private List<ErDiagramResponse.ErColumn> mergeColumns(List<ErDiagramResponse.ErColumn> loaded,
                                                          List<ErDiagramResponse.ErColumn> stub) {
        if (loaded.isEmpty()) {
            return stub;
        }
        Set<String> names = new HashSet<>();
        for (ErDiagramResponse.ErColumn column : loaded) {
            if (column.getName() != null) {
                names.add(column.getName().toLowerCase(Locale.ROOT));
            }
        }
        List<ErDiagramResponse.ErColumn> merged = new ArrayList<>(loaded);
        for (ErDiagramResponse.ErColumn column : stub) {
            if (column.getName() != null && names.add(column.getName().toLowerCase(Locale.ROOT))) {
                merged.add(column);
            }
        }
        return merged;
    }

    private static String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }
}
