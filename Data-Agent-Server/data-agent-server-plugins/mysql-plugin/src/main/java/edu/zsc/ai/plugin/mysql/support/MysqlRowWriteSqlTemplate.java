package edu.zsc.ai.plugin.mysql.support;

import edu.zsc.ai.plugin.mysql.constant.MySqlTemplate;

import java.util.Collections;
import java.util.List;

final class MysqlRowWriteSqlTemplate {

    String buildInsertRowSql(String fullTableName, List<String> quotedColumns) {
        return buildInsertRowSql(fullTableName, quotedColumns,
                Collections.nCopies(quotedColumns.size(), "?"));
    }

    /** Same template with caller-rendered values (placeholders or inline literals). */
    String buildInsertRowSql(String fullTableName, List<String> quotedColumns, List<String> renderedValues) {
        return String.format(
                MySqlTemplate.SQL_INSERT_TABLE_ROW,
                fullTableName,
                String.join(", ", quotedColumns),
                String.join(", ", renderedValues)
        );
    }

    String buildDeleteRowSql(String fullTableName, String whereSql) {
        return String.format(MySqlTemplate.SQL_DELETE_TABLE_ROW, fullTableName, whereSql);
    }

    String buildUpdateRowSql(String fullTableName, String setClauseSql, String whereSql) {
        return String.format(MySqlTemplate.SQL_UPDATE_TABLE_ROW, fullTableName, setClauseSql, whereSql);
    }

    String buildCountMatchingRowsSql(String fullTableName, String whereSql) {
        return String.format(MySqlTemplate.SQL_COUNT_MATCHING_TABLE_ROWS, fullTableName, whereSql);
    }
}
