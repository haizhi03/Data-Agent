package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.ColumnSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.IndexSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/** 主键比较：只比较列清单及其顺序。 */
@Component
public class PrimaryKeyComparator extends AbstractSchemaObjectComparator {

    private static final String OBJECT_KEY = "PRIMARY KEY";

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.PRIMARY_KEY;
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        List<String> columns = table.columns().stream()
                .filter(ColumnSnapshot::primaryKeyPart)
                .map(ColumnSnapshot::name)
                .toList();
        if (columns.isEmpty()) {
            // 部分驱动不在列元数据里标记主键，退回索引信息。
            columns = table.indexes().stream()
                    .filter(IndexSnapshot::primaryKey)
                    .findFirst()
                    .map(IndexSnapshot::columns)
                    .orElse(List.of());
        }
        if (columns.isEmpty()) {
            return List.of();
        }
        List<String> normalized = columns.stream()
                .map(context.rules()::normalizeIdentifier)
                .toList();
        return List.of(item(OBJECT_KEY, attributes("COLUMNS", join(normalized))));
    }
}
