package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.ConstraintSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/** CHECK 约束比较：表达式按去空白后的文本比较。 */
@Component
public class CheckConstraintComparator extends AbstractSchemaObjectComparator {

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.CHECK;
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        return table.constraints().stream()
                .filter(constraint -> "CHECK".equals(constraintType(constraint.type())))
                .map(constraint -> checkItem(constraint, context))
                .toList();
    }

    private Item checkItem(ConstraintSnapshot constraint, CompareContext context) {
        String expression = normalizeExpression(constraint.checkExpression());
        String key = constraint.name() != null && !constraint.name().isBlank()
                ? context.rules().normalizeIdentifier(constraint.name())
                : expression;
        return item(key == null ? "CHECK" : key, attributes("EXPRESSION", expression));
    }

    /** CHECK 表达式只压缩空白，不改变大小写，避免把字符串字面量判成不同。 */
    private static String normalizeExpression(String expression) {
        if (expression == null) return null;
        String value = expression.replaceAll("\\s+", " ").trim();
        return value.isEmpty() ? null : value;
    }
}
