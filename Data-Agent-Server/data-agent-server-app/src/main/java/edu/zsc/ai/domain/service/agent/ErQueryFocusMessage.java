package edu.zsc.ai.domain.service.agent;

import edu.zsc.ai.api.model.request.ErQueryFocus;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.MessageSource;

/**
 * Assembles the circled ER tables as model context in front of the user message.
 * Labels come from the message bundles. The stored user text stays unchanged.
 */
public final class ErQueryFocusMessage {

    static final int TABLE_DETAIL_LIMIT = 16;
    static final int COLUMN_LIMIT = 40;

    private ErQueryFocusMessage() {
    }

    public static String enrich(String message, ErQueryFocus focus, MessageSource messages, Locale locale) {
        if (focus == null || focus.getTables() == null || focus.getTables().isEmpty()) {
            return message;
        }
        StringBuilder block = new StringBuilder();
        block.append(text(messages, locale, "er.focus.subject")).append('\n');
        block.append(text(messages, locale, "er.focus.connection",
                StringUtils.defaultString(focus.getConnectionName()),
                focus.getConnectionId() == null ? "" : focus.getConnectionId().toString())).append('\n');
        appendTables(block, focus.getTables(), messages, locale);
        appendRelations(block, text(messages, locale, "er.focus.inside"), focus.getInsideRelations(), messages, locale);
        appendRelations(block, text(messages, locale, "er.focus.outside"), focus.getOutsideRelations(), messages, locale);
        String question = StringUtils.defaultString(message).trim();
        if (question.isEmpty()) {
            return block.toString().trim();
        }
        return block + "\n" + text(messages, locale, "er.focus.question") + "\n" + question;
    }

    private static void appendTables(StringBuilder block, List<ErQueryFocus.Table> tables, MessageSource messages, Locale locale) {
        block.append(text(messages, locale, "er.focus.tables")).append('\n');
        int detailed = 0;
        for (ErQueryFocus.Table table : tables) {
            if (table == null || StringUtils.isBlank(table.getName())) {
                continue;
            }
            String qualified = qualify(table.getCatalog(), table.getSchema(), table.getName());
            if (detailed >= TABLE_DETAIL_LIMIT) {
                block.append("- ").append(qualified).append('\n');
                continue;
            }
            detailed++;
            block.append("- ").append(qualified).append(':');
            List<ErQueryFocus.Column> columns = table.getColumns();
            if (columns == null || columns.isEmpty()) {
                block.append(text(messages, locale, "er.focus.no-columns")).append('\n');
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

    private static void appendRelations(
            StringBuilder block,
            String title,
            List<ErQueryFocus.Relation> relations,
            MessageSource messages,
            Locale locale) {
        block.append(title).append('\n');
        if (relations == null || relations.isEmpty()) {
            block.append(text(messages, locale, "er.focus.none")).append('\n');
            return;
        }
        for (ErQueryFocus.Relation relation : relations) {
            if (relation == null) {
                continue;
            }
            block.append("- ")
                    .append(end(relation.getFromCatalog(), relation.getFromSchema(), relation.getFromTable(), relation.getFromColumns()))
                    .append(" -> ")
                    .append(end(relation.getToCatalog(), relation.getToSchema(), relation.getToTable(), relation.getToColumns()));
            if (StringUtils.isNotBlank(relation.getCardinality())) {
                block.append(' ').append(relation.getCardinality());
            }
            if (StringUtils.isNotBlank(relation.getViaTable())) {
                block.append(text(messages, locale, "er.focus.via"))
                        .append(qualify(relation.getViaCatalog(), relation.getViaSchema(), relation.getViaTable()));
            }
            block.append('\n');
        }
    }

    private static String end(String catalog, String schema, String table, List<String> columns) {
        String name = qualify(catalog, schema, table);
        if (columns == null || columns.isEmpty()) {
            return name;
        }
        return name + "." + String.join(",", columns);
    }

    static String qualify(String catalog, String schema, String table) {
        String name = StringUtils.defaultIfBlank(table, "?");
        if (StringUtils.isNotBlank(catalog) && StringUtils.isNotBlank(schema)) {
            return catalog + "." + schema + "." + name;
        }
        if (StringUtils.isNotBlank(schema)) {
            return schema + "." + name;
        }
        if (StringUtils.isNotBlank(catalog)) {
            return catalog + "." + name;
        }
        return name;
    }

    private static String text(MessageSource messages, Locale locale, String code, Object... args) {
        return messages.getMessage(code, args.length == 0 ? null : args, locale);
    }
}
