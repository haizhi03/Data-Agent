package edu.zsc.ai.domain.service.db.sqlgen;

import edu.zsc.ai.domain.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Indexes every {@link SqlTemplateStrategy} bean by statement type.
 */
@Component
public class SqlTemplateRegistry {

    private final Map<String, SqlTemplateStrategy> strategies;

    public SqlTemplateRegistry(List<SqlTemplateStrategy> strategyList) {
        Map<String, SqlTemplateStrategy> map = new HashMap<>();
        for (SqlTemplateStrategy strategy : strategyList) {
            map.put(normalize(strategy.statementType()), strategy);
        }
        this.strategies = Map.copyOf(map);
    }

    public SqlTemplateStrategy resolve(String statementType) {
        SqlTemplateStrategy strategy = strategies.get(normalize(statementType));
        if (strategy == null) {
            throw new BusinessException("Unsupported SQL statement type: " + statementType);
        }
        return strategy;
    }

    private static String normalize(String statementType) {
        return statementType == null ? "" : statementType.trim().toUpperCase(Locale.ROOT);
    }
}
