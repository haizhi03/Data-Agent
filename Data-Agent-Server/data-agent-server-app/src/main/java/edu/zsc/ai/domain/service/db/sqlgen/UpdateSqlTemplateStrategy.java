package edu.zsc.ai.domain.service.db.sqlgen;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class UpdateSqlTemplateStrategy implements SqlTemplateStrategy {

    @Override
    public String statementType() {
        return "UPDATE";
    }

    @Override
    public String generate(SqlTemplateContext context) {
        List<String> whereColumns = context.primaryKeyColumns().isEmpty()
                ? context.columns()
                : context.primaryKeyColumns();
        List<String> setColumns = new ArrayList<>(context.columns());
        setColumns.removeAll(whereColumns);
        if (setColumns.isEmpty()) {
            setColumns.addAll(context.columns());
        }

        List<String> assignments = setColumns.stream()
                .map(column -> "  " + column + " = NULL")
                .toList();
        String where = whereColumns.stream()
                .map(column -> "  " + column + " = NULL")
                .reduce((left, right) -> left + " AND\n" + right)
                .orElse("");

        return "UPDATE " + context.tableName()
                + "\nSET\n" + String.join(",\n", assignments)
                + "\nWHERE\n" + where + ";";
    }
}
