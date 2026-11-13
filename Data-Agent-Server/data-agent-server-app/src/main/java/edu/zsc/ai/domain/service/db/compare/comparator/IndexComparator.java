package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.IndexSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 索引比较：唯一性、索引类型、列清单及顺序；主键索引由主键比较器负责，此处跳过。 */
@Component
public class IndexComparator extends AbstractSchemaObjectComparator {

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.INDEX;
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        List<Item> items = new ArrayList<>();
        for (IndexSnapshot index : table.indexes()) {
            if (index.primaryKey()) {
                continue;
            }
            List<String> columns = index.columns().stream()
                    .map(context.rules()::normalizeIdentifier)
                    .toList();
            items.add(item(
                    context.rules().normalizeIdentifier(index.name()),
                    attributes(
                            "UNIQUE", yesNo(index.unique()),
                            "TYPE", index.type() == null ? null : index.type().trim().toUpperCase(Locale.ROOT),
                            "COLUMNS", join(columns)
                    )
            ));
        }
        return items;
    }
}
