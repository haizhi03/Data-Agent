package edu.zsc.ai.domain.service.db.sqlgen;

import org.springframework.stereotype.Component;

@Component
public class SelectSqlTemplateStrategy implements SqlTemplateStrategy {

    @Override
    public String statementType() {
        return "SELECT";
    }

    @Override
    public String generate(SqlTemplateContext context) {
        return "SELECT " + String.join(", ", context.columns())
                + "\nFROM " + context.tableName() + ";";
    }
}
