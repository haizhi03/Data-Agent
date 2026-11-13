package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/** 表注释比较；列注释在列比较器里逐列处理。 */
@Component
public class TableCommentComparator extends AbstractSchemaObjectComparator {

    private static final String OBJECT_KEY = "TABLE";

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.COMMENT;
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        if (context.ignoreComments()) {
            return List.of();
        }
        return List.of(item(OBJECT_KEY, attributes(
                "COMMENT", context.rules().normalizeComment(table.comment())
        )));
    }
}
