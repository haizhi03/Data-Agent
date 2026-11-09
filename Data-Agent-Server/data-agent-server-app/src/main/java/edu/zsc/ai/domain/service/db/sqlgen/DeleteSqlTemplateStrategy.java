package edu.zsc.ai.domain.service.db.sqlgen;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DeleteSqlTemplateStrategy implements SqlTemplateStrategy {

    @Override
    public String statementType() {
        return "DELETE";
    }

    @Override
    public String generate(SqlTemplateContext context) {
        List<String> whereColumns = context.primaryKeyColumns().isEmpty()
                ? context.columns()
                : context.primaryKeyColumns();
        String where = whereColumns.stream()
                .map(column -> "  " + column + " = NULL")
                .reduce((left, right) -> left + " AND\n" + right)
                .orElse("");

        return "DELETE FROM " + context.tableName()
                + "\nWHERE\n" + where + ";";
    }
}
