package edu.zsc.ai.domain.service.agent;

import edu.zsc.ai.api.model.request.ErQueryFocus;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/**
 * Puts the circled ER tables in front of the user message the agent sees.
 * The stored user text stays unchanged.
 */
public final class ErQueryFocusMessage {

    static final int TABLE_DETAIL_LIMIT = 16;
    static final int COLUMN_LIMIT = 40;

    private ErQueryFocusMessage() {
    }

    public static String enrich(String message, ErQueryFocus focus, String language) {
        if (focus == null || focus.getTables() == null || focus.getTables().isEmpty()) {
            return message;
        }
        boolean chinese = language != null && language.toLowerCase(Locale.ROOT).startsWith("zh");
        StringBuilder block = new StringBuilder();
        block.append(chinese
                ? "这些表是这次问题的主语。可以沿外键向外补一跳，用来解释圈中的表；补了要写明原因。不要换成名字相似的另一套表。圈选不是白名单。\n"
                : "These tables are the subject of this question. You may follow one foreign-key hop to explain them, and say why that table was added. Do not switch to a similarly named set of tables. The selection is not a closed whitelist.\n");
        appendTables(block, focus.getTables(), chinese);
        appendRelations(block, chinese ? "圈内的外键：" : "Foreign keys inside the selection:", focus.getInsideRelations(), chinese);
        appendRelations(block, chinese
                ? "只有一端在圈里的外键（另一端只给了表名和关联列）："
                : "Foreign keys with one end outside the selection (only that table name and the join columns):",
                focus.getOutsideRelations(), chinese);
        String question = StringUtils.defaultString(message).trim();
        if (question.isEmpty()) {
            return block.toString().trim();
        }
        return block + (chinese ? "\n用户问题：\n" : "\nUser question:\n") + question;
    }

    private static void appendTables(StringBuilder block, List<ErQueryFocus.Table> tables, boolean chinese) {
        block.append(chinese ? "圈中的表：\n" : "Circled tables:\n");
        int detailed = 0;
        for (ErQueryFocus.Table table : tables) {
            if (table == null || StringUtils.isBlank(table.getName())) {
                continue;
            }
            if (detailed >= TABLE_DETAIL_LIMIT) {
                block.append("- ").append(table.getName()).append('\n');
                continue;
            }
            detailed++;
            block.append("- ").append(table.getName());
            String scope = StringUtils.firstNonBlank(table.getSchema(), table.getCatalog());
            if (StringUtils.isNotBlank(scope)) {
                block.append(" (").append(scope).append(')');
            }
            block.append(':');
            List<ErQueryFocus.Column> columns = table.getColumns();
            if (columns == null || columns.isEmpty()) {
                block.append(chinese ? " （没有字段）\n" : " (no columns)\n");
                continue;
            }
            int shown = 0;
            for (ErQueryFocus.Column column : columns) {
                if (column == null || StringUtils.isBlank(column.getName()) || shown >= COLUMN_LIMIT) {
                    continue;
                }
                if (shown > 0) {
                    block.append(',');
                }
                shown++;
                block.append(' ').append(column.getName());
                if (column.isPrimaryKey()) {
                    block.append(" PK");
                }
                if (StringUtils.isNotBlank(column.getTypeName())) {
                    block.append(' ').append(column.getTypeName());
                }
            }
            block.append('\n');
        }
    }

    private static void appendRelations(StringBuilder block, String title, List<ErQueryFocus.Relation> relations, boolean chinese) {
        block.append(title).append('\n');
        if (relations == null || relations.isEmpty()) {
            block.append(chinese ? "无\n" : "none\n");
            return;
        }
        for (ErQueryFocus.Relation relation : relations) {
            if (relation == null) {
                continue;
            }
            block.append("- ")
                    .append(end(relation.getFromTable(), relation.getFromColumns()))
                    .append(" -> ")
                    .append(end(relation.getToTable(), relation.getToColumns()));
            if (StringUtils.isNotBlank(relation.getCardinality())) {
                block.append(' ').append(relation.getCardinality());
            }
            if (StringUtils.isNotBlank(relation.getViaTable())) {
                block.append(chinese ? " 经由 " : " via ").append(relation.getViaTable());
            }
            block.append('\n');
        }
    }

    private static String end(String table, List<String> columns) {
        String name = StringUtils.defaultIfBlank(table, "?");
        if (columns == null || columns.isEmpty()) {
            return name;
        }
        return name + "." + String.join(",", columns);
    }
}
