package edu.zsc.ai.domain.service.db.compare.snapshot;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ColumnService;
import edu.zsc.ai.domain.service.db.ConstraintService;
import edu.zsc.ai.domain.service.db.IndexService;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.domain.service.db.compare.diff.CompareOptions;
import edu.zsc.ai.domain.service.db.compare.model.ColumnSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.ConstraintSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.IndexSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.SchemaSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import edu.zsc.ai.plugin.model.metadata.IndexMetadata;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把某个库/模式的结构读成与方言无关的快照。
 * 复用现有表/列/索引/约束服务，不新增 SQL；单表读取异常只影响该表。
 */
@Component
@RequiredArgsConstructor
public class SchemaSnapshotReader {

    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotReader.class);

    private final TableService tableService;
    private final ColumnService columnService;
    private final IndexService indexService;
    private final ConstraintService constraintService;

    public SchemaSnapshot read(DbContext db, CompareOptions options) {
        List<String> tableNames = tableService.getTables(db);
        Map<String, String> comments = loadComments(db);

        List<TableSnapshot> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            if (!options.includes(tableName)) {
                continue;
            }
            tables.add(readTable(db, tableName, lookupComment(comments, tableName)));
        }
        return new SchemaSnapshot(db.catalog(), db.schema(), tables);
    }

    private TableSnapshot readTable(DbContext db, String tableName, String comment) {
        List<ColumnSnapshot> columns = List.of();
        List<IndexSnapshot> indexes = List.of();
        List<ConstraintSnapshot> constraints = List.of();
        String columnError = null;
        List<String> auxiliaryErrors = new ArrayList<>();

        try {
            columns = columnService.listColumns(db, tableName).stream()
                    .map(this::toColumnSnapshot)
                    .toList();
        } catch (Exception e) {
            columnError = "columns: " + message(e);
        }

        try {
            indexes = indexService.getIndexes(db, tableName).stream()
                    .map(this::toIndexSnapshot)
                    .toList();
        } catch (Exception e) {
            auxiliaryErrors.add("indexes: " + message(e));
        }

        try {
            constraints = constraintService.getConstraints(db, tableName).stream()
                    .map(this::toConstraintSnapshot)
                    .toList();
        } catch (Exception e) {
            // MySQL 等插件尚未实现约束能力，这里降级为“无约束信息”，不把整张表判成读取失败。
            auxiliaryErrors.add("constraints: " + message(e));
        }

        if (columnError != null) {
            log.warn("Schema compare: cannot read columns of {}.{}: {}", db.schema(), tableName, columnError);
        }
        String auxiliaryError = auxiliaryErrors.isEmpty() ? null : String.join("; ", auxiliaryErrors);
        return new TableSnapshot(tableName, comment, columns, indexes, constraints, columnError, auxiliaryError);
    }

    private Map<String, String> loadComments(DbContext db) {
        try {
            Map<String, String> comments = tableService.getTableComments(db);
            return comments == null ? Map.of() : comments;
        } catch (Exception e) {
            log.warn("Schema compare: cannot read table comments: {}", message(e));
            return Map.of();
        }
    }

    private static String lookupComment(Map<String, String> comments, String tableName) {
        String comment = comments.get(tableName);
        if (comment != null) {
            return comment;
        }
        for (Map.Entry<String, String> entry : comments.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(tableName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private ColumnSnapshot toColumnSnapshot(ColumnMetadata column) {
        return new ColumnSnapshot(
                column.name(),
                column.ordinalPosition(),
                column.typeName(),
                column.columnSize(),
                column.decimalDigits(),
                column.nullable(),
                column.defaultValue(),
                column.remarks(),
                column.isAutoIncrement(),
                column.isPrimaryKeyPart()
        );
    }

    private IndexSnapshot toIndexSnapshot(IndexMetadata index) {
        return new IndexSnapshot(
                index.name(),
                index.type(),
                index.columns() == null ? List.of() : List.copyOf(index.columns()),
                index.unique(),
                index.isPrimaryKey()
        );
    }

    private ConstraintSnapshot toConstraintSnapshot(ConstraintMetadata constraint) {
        return new ConstraintSnapshot(
                constraint.name(),
                constraint.type(),
                constraint.columns() == null ? List.of() : List.copyOf(constraint.columns()),
                constraint.referencedSchema(),
                constraint.referencedTable(),
                constraint.referencedColumns() == null ? List.of() : List.copyOf(constraint.referencedColumns()),
                constraint.checkExpression(),
                constraint.enabled()
        );
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.trim();
    }
}
