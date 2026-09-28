package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ErDiagramResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.ErDiagramService;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.constant.JdbcMetaDataConstants;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                        .comment(comments.getOrDefault(tableName.toLowerCase(Locale.ROOT), ""))
                        .columns(toColumns(columns.getColumns(connection, db.catalog(), db.schema(), tableName)))
                        .build());
            }

            List<ErDiagramResponse.ErRelation> relations = loadRelations(
                    connection.getMetaData(), db.catalog(), db.schema(), tableNames, canonicalNames);
            return ErDiagramResponse.builder()
                    .tables(erTables)
                    .relations(relations)
                    .truncated(truncated)
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

    private List<ErDiagramResponse.ErRelation> loadRelations(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            List<String> tableNames,
            Map<String, String> canonicalNames) {
        List<ErDiagramResponse.ErRelation> relations = new ArrayList<>();
        for (String tableName : tableNames) {
            try (ResultSet rs = metaData.getImportedKeys(catalog, schema, tableName)) {
                while (rs.next()) {
                    String fkTable = canonical(rs.getString(JdbcMetaDataConstants.FKTABLE_NAME), canonicalNames);
                    String pkTable = canonical(rs.getString(JdbcMetaDataConstants.PKTABLE_NAME), canonicalNames);
                    String fkColumn = rs.getString(JdbcMetaDataConstants.FKCOLUMN_NAME);
                    String pkColumn = rs.getString(JdbcMetaDataConstants.PKCOLUMN_NAME);
                    if (fkTable == null || pkTable == null || StringUtils.isAnyBlank(fkColumn, pkColumn)) {
                        continue;
                    }
                    relations.add(ErDiagramResponse.ErRelation.builder()
                            .name(StringUtils.defaultString(rs.getString(JdbcMetaDataConstants.FK_NAME)))
                            .fromTable(fkTable)
                            .fromColumn(fkColumn)
                            .toTable(pkTable)
                            .toColumn(pkColumn)
                            .build());
                }
            } catch (SQLException e) {
                log.debug("Foreign keys unavailable for {}: {}", tableName, e.getMessage());
            }
        }
        return relations;
    }

    private String canonical(String name, Map<String, String> canonicalNames) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        return canonicalNames.get(name.toLowerCase(Locale.ROOT));
    }
}
