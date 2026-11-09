package edu.zsc.ai.plugin.dm.manager;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;

/**
 * DM column manager. Reuses the SPI default {@code DatabaseMetaData.getColumns}
 * implementation, then supplements comments and identity metadata from DM dictionary tables.
 */
public final class DmColumnManager implements ColumnManager {

    private final DmMetadataSupport support;

    public DmColumnManager(DmMetadataSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<ColumnMetadata> getColumns(Connection connection, String catalog, String schema, String tableOrViewName) {
        List<ColumnMetadata> columns = ColumnManager.super.getColumns(connection, catalog, schema, tableOrViewName);
        if (columns.isEmpty() || connection == null || StringUtils.isBlank(tableOrViewName)) {
            return columns;
        }

        String owner = support.resolveSchema(connection, schema);
        String exactTableName = tableOrViewName.trim();
        boolean anyRemarkMissing = columns.stream().anyMatch(c -> StringUtils.isBlank(c.remarks()));

        Map<String, String> commentsByColumn = new HashMap<>();
        if (anyRemarkMissing) {
            for (String[] columnComment : support.getColumnComments(connection, owner, exactTableName)) {
                if (StringUtils.isNotBlank(columnComment[0]) && StringUtils.isNotBlank(columnComment[1])) {
                    commentsByColumn.put(columnComment[0], columnComment[1]);
                }
            }
        }
        Set<String> autoIncrementColumns = support.getAutoIncrementColumns(connection, owner, exactTableName);

        List<ColumnMetadata> enriched = new ArrayList<>(columns.size());
        for (ColumnMetadata column : columns) {
            String remarks = column.remarks();
            if (StringUtils.isBlank(remarks) && column.name() != null) {
                remarks = commentsByColumn.getOrDefault(column.name(), remarks);
            }
            boolean autoIncrement = column.isAutoIncrement()
                    || column.name() != null && autoIncrementColumns.contains(column.name());
            if (Objects.equals(remarks, column.remarks()) && autoIncrement == column.isAutoIncrement()) {
                enriched.add(column);
                continue;
            }
            enriched.add(new ColumnMetadata(
                    column.name(),
                    column.dataType(),
                    column.typeName(),
                    column.columnSize(),
                    column.decimalDigits(),
                    column.nullable(),
                    column.ordinalPosition(),
                    remarks,
                    column.isPrimaryKeyPart(),
                    autoIncrement,
                    column.isUnsigned(),
                    column.defaultValue()
            ));
        }
        return enriched;
    }
}
