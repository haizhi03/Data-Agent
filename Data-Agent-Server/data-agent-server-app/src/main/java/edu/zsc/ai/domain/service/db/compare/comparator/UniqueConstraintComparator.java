package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.ConstraintSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/** 唯一约束比较：列清单及顺序。部分数据库把唯一约束暴露成唯一索引，由索引比较器覆盖。 */
@Component
public class UniqueConstraintComparator extends AbstractSchemaObjectComparator {

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.UNIQUE;
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        return table.constraints().stream()
                .filter(constraint -> "UNIQUE".equals(constraintType(constraint.type())))
                .map(constraint -> uniqueItem(constraint, context))
                .toList();
    }

    private Item uniqueItem(ConstraintSnapshot constraint, CompareContext context) {
        List<String> columns = constraint.columns().stream()
                .map(context.rules()::normalizeIdentifier)
                .toList();
        String key = constraint.name() != null && !constraint.name().isBlank()
                ? context.rules().normalizeIdentifier(constraint.name())
                : join(columns);
        return item(key, attributes("COLUMNS", join(columns)));
    }
}
