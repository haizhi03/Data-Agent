package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.ConstraintSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/** 外键比较：本地列顺序、引用表、引用列顺序。 */
@Component
public class ForeignKeyComparator extends AbstractSchemaObjectComparator {

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.FOREIGN_KEY;
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        return table.constraints().stream()
                .filter(constraint -> "FOREIGN_KEY".equals(constraintType(constraint.type())))
                .map(constraint -> foreignKeyItem(constraint, context))
                .toList();
    }

    private Item foreignKeyItem(ConstraintSnapshot constraint, CompareContext context) {
        List<String> columns = constraint.columns().stream()
                .map(context.rules()::normalizeIdentifier)
                .toList();
        List<String> referencedColumns = constraint.referencedColumns().stream()
                .map(context.rules()::normalizeIdentifier)
                .toList();
        String referencedTable = context.rules().normalizeIdentifier(constraint.referencedTable());
        String key = constraint.name() != null && !constraint.name().isBlank()
                ? context.rules().normalizeIdentifier(constraint.name())
                : join(columns) + "->" + referencedTable;
        return item(key, attributes(
                "COLUMNS", join(columns),
                "REFERENCES_TABLE", referencedTable,
                "REFERENCES_COLUMNS", join(referencedColumns)
        ));
    }
}
