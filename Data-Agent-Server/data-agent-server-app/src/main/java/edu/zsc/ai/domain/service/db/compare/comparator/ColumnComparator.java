package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.ColumnSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 列比较：类型、长度、精度、可空、默认值、自增、注释、列顺序。 */
@Component
public class ColumnComparator extends AbstractSchemaObjectComparator {

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.COLUMN;
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        List<Item> items = new ArrayList<>();
        for (ColumnSnapshot column : table.columns()) {
            Map<String, String> attributes = attributes(
                    "TYPE", context.rules().normalizeTypeName(column.typeName()),
                    "LENGTH", String.valueOf(column.columnSize()),
                    "SCALE", String.valueOf(column.decimalDigits()),
                    "NULLABLE", yesNo(column.nullable()),
                    "DEFAULT", context.rules().normalizeDefault(column.defaultValue()),
                    "AUTO_INCREMENT", yesNo(column.autoIncrement())
            );
            if (!context.ignoreComments()) {
                attributes.put("COMMENT", context.rules().normalizeComment(column.comment()));
            }
            if (!context.ignoreColumnOrder()) {
                attributes.put("POSITION", String.valueOf(column.ordinalPosition()));
            }
            items.add(item(context.rules().normalizeIdentifier(column.name()), attributes));
        }
        return items;
    }
}
