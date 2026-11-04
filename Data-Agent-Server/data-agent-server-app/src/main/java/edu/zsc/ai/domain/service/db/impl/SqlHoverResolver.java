package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.SqlHoverResponse;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import edu.zsc.ai.plugin.model.sql.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves the identifier chain under the cursor to a hover card. Parser-level
 * sources (variables, aliases, CTEs, derived tables, built-in functions) work
 * without a database; physical objects need the metadata resolver, which is
 * null when the connection is unavailable.
 */
final class SqlHoverResolver {
    private static final int MAX_COLUMNS = 40;

    private final DbContext db;
    private final String dataSource;
    private final CompletionContext context;
    private final SqlCompletionMetadataResolver metadata;
    private final List<CompletionFunction> builtins;

    SqlHoverResolver(DbContext db, String dataSource, CompletionContext context,
                     SqlCompletionMetadataResolver metadata, List<CompletionFunction> builtins) {
        this.db = db;
        this.dataSource = dataSource;
        this.context = context;
        this.metadata = metadata;
        this.builtins = builtins == null ? List.of() : builtins;
    }

    SqlHoverResponse resolve(SqlTextScanner.Chain chain) {
        // The hovered segment decides how much of the chain to interpret: hovering `alias`
        // in `alias.column` describes the alias target, hovering `column` describes the column.
        List<SqlTextScanner.Segment> segments = chain.segments().subList(0, chain.hoveredIndex() + 1);
        String markdown = switch (segments.size()) {
            case 1 -> single(segments.get(0));
            case 2 -> qualified(segments.get(0), segments.get(1));
            case 3 -> column(schemaContext(segments.get(0).key()), segments.get(1).key(),
                    segments.get(2), display(segments.get(0).key(), segments.get(1).key()));
            default -> null;
        };
        if (markdown == null || markdown.isBlank()) return SqlHoverResponse.empty();
        return new SqlHoverResponse(markdown, chain.hovered().start(), chain.hovered().end());
    }

    private String single(SqlTextScanner.Segment segment) {
        for (CompletionVariable variable : context.variables()) {
            if (matches(variable.name(), segment))
                return "**VARIABLE** `" + code(variable.name().name()) + "` : `" + code(Objects.toString(variable.type(), "")) + "`";
        }
        for (CompletionRelation relation : context.relations()) {
            if (!matches(relation.qualifier(), segment)) continue;
            if (relation.logical()) return queryHover(relation.qualifier(), relation.query(), segment);
            if (relation.table() != null) return tableHover(relation.table(), relation.aliased() ? relation.qualifier() : null);
        }
        CompletionQuery cte = context.commonTables().get(segment.key());
        if (cte != null) return queryHover(CompletionIdentifier.unquoted(segment.key()), cte, segment);
        if (metadata != null) {
            String kind = metadata.objectKind(db, segment.key());
            List<ColumnMetadata> columns = metadata.columns(db, segment.key());
            if (kind != null || !columns.isEmpty())
                return tableMarkdown(kind == null ? "TABLE" : kind, db.schema(), segment.key(), null, columns);
            for (CompletionFunction function : builtins) {
                if (function.name().equalsIgnoreCase(segment.name()) && !segment.quoted())
                    return functionMarkdown(function.signature());
            }
            if (metadata.packages(db).contains(segment.key()))
                return "**PACKAGE** `" + code(db.schema()) + "`.`" + code(segment.key()) + "`";
        } else {
            for (CompletionFunction function : builtins) {
                if (function.name().equalsIgnoreCase(segment.name()) && !segment.quoted())
                    return functionMarkdown(function.signature());
            }
        }
        return null;
    }

    private String qualified(SqlTextScanner.Segment qualifier, SqlTextScanner.Segment member) {
        for (CompletionRelation relation : context.relations()) {
            if (!matches(relation.qualifier(), qualifier)) continue;
            if (relation.logical()) return queryColumnHover(relation.qualifier(), relation.query(), member);
            if (relation.table() != null && metadata != null) {
                CompletionTableRef table = relation.table();
                String owner = display(table.schema() == null ? db.schema() : table.schema(), table.name());
                return column(schemaContext(table.schema() == null ? db.schema() : table.schema()), table.name(), member, owner);
            }
            return null;
        }
        if (metadata == null) return null;
        if (metadata.packages(db).contains(qualifier.key())) {
            List<CompletionRoutine> overloads = metadata.packageMembers(db, qualifier.key()).stream()
                    .filter(r -> matches(r.name(), member)).toList();
            if (!overloads.isEmpty()) return routineMarkdown(overloads);
        }
        List<ColumnMetadata> columns = metadata.columns(schemaContext(qualifier.key()), member.key());
        if (!columns.isEmpty())
            return tableMarkdown(Objects.toString(metadata.objectKind(schemaContext(qualifier.key()), member.key()), "TABLE"),
                    qualifier.key(), member.key(), null, columns);
        return null;
    }

    /** Column hover for schema.table.column or relation-qualified references resolved by the caller. */
    private String column(DbContext source, String table, SqlTextScanner.Segment name, String owner) {
        if (source == null || metadata == null) return null;
        for (ColumnMetadata column : metadata.columns(source, table)) {
            if (matches(column.name(), name))
                return columnMarkdown(owner == null ? table : owner, column);
        }
        return null;
    }

    private DbContext schemaContext(String schema) {
        if (schema == null || schema.isBlank()) return null;
        return new DbContext(db.connectionId(), db.catalog(), schema);
    }

    private static boolean matches(CompletionIdentifier identifier, SqlTextScanner.Segment segment) {
        return identifier.name().equals(segment.key());
    }

    private static boolean matches(String metadataName, SqlTextScanner.Segment segment) {
        return metadataName.equals(segment.key());
    }

    private String queryHover(CompletionIdentifier qualifier, CompletionQuery query, SqlTextScanner.Segment segment) {
        String kind = context.commonTables().containsKey(qualifier.name()) ? "CTE" : "DERIVED TABLE";
        List<String> names = outputNames(query);
        StringBuilder body = new StringBuilder("**" + kind + "** `" + code(qualifier.name()) + "`");
        if (!names.isEmpty()) body.append("\n\nColumns: ").append(String.join(", ", names.stream().map(SqlHoverResolver::backtick).toList()));
        return body.toString();
    }

    private String queryColumnHover(CompletionIdentifier qualifier, CompletionQuery query, SqlTextScanner.Segment member) {
        for (String output : outputNames(query)) {
            if (output.equals(member.key()))
                return "**COLUMN** `" + code(qualifier.name()) + "`.`" + code(output) + "`\n\n*query output column*";
        }
        return null;
    }

    private static List<String> outputNames(CompletionQuery query) {
        if (query == null) return List.of();
        if (!query.explicitColumns().isEmpty())
            return query.explicitColumns().stream().map(CompletionIdentifier::name).toList();
        return query.projections().stream().filter(p -> p.output() != null).map(p -> p.output().name()).toList();
    }

    private String tableHover(CompletionTableRef table, CompletionIdentifier alias) {
        if (metadata == null) return null;
        String schema = table.schema() == null ? db.schema() : table.schema();
        List<ColumnMetadata> columns = metadata.columns(schemaContext(schema), table.name());
        if (columns.isEmpty()) return null;
        String kind = Objects.toString(metadata.objectKind(schemaContext(schema), table.name()), "TABLE");
        return tableMarkdown(kind, schema, table.name(), alias == null ? null : alias.name(), columns);
    }

    /** DataGrip-style card: data source / schema / object header, comment, then DDL or a column table. */
    private String tableMarkdown(String kind, String schema, String table, String alias, List<ColumnMetadata> columns) {
        StringBuilder body = new StringBuilder();
        if (dataSource != null && !dataSource.isBlank()) body.append("**数据源** `").append(code(dataSource)).append("`  \n");
        body.append("**架构** `").append(code(schema)).append("`  \n");
        body.append("**").append(kindLabel(kind)).append("** `").append(code(table)).append('`');
        if (alias != null) body.append(" — 别名 `").append(code(alias)).append('`');
        DbContext source = schemaContext(schema);
        String comment = metadata == null ? null : metadata.tableComment(source, table);
        if (comment != null && !comment.isBlank()) body.append("\n\n").append(text(comment));
        String ddl = metadata == null || !"TABLE".equals(kind) ? null : metadata.tableDdl(source, table);
        if (ddl != null && !ddl.isBlank()) {
            body.append("\n\n```sql\n").append(ddl.strip()).append("\n```");
            return body.toString().strip();
        }
        if (!columns.isEmpty()) {
            body.append("\n\n| Column | Type | Attributes | Comment |\n| --- | --- | --- | --- |\n");
            int shown = Math.min(columns.size(), MAX_COLUMNS);
            for (int i = 0; i < shown; i++) {
                ColumnMetadata column = columns.get(i);
                body.append("| `").append(code(column.name())).append("` | `").append(code(type(column))).append("` | ")
                        .append(text(attributes(column))).append(" | ").append(text(column.remarks())).append(" |\n");
            }
            if (columns.size() > shown) body.append("\n*… and ").append(columns.size() - shown).append(" more columns*");
        }
        return body.toString().strip();
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "TABLE" -> "表";
            case "VIEW" -> "视图";
            default -> kind;
        };
    }

    private static String columnMarkdown(String owner, ColumnMetadata column) {
        StringBuilder body = new StringBuilder("**COLUMN** `" + code(owner) + "`.`" + code(column.name()) + "` : `")
                .append(code(type(column))).append('`');
        List<String> facts = new ArrayList<>();
        if (!attributes(column).isBlank()) facts.add(attributes(column));
        if (column.remarks() != null && !column.remarks().isBlank()) facts.add(text(column.remarks()));
        if (!facts.isEmpty()) body.append("\n\n").append(String.join(" · ", facts));
        return body.toString();
    }

    private static String functionMarkdown(String signature) {
        return "```sql\n" + signature + "\n```";
    }

    private static String routineMarkdown(List<CompletionRoutine> overloads) {
        StringBuilder body = new StringBuilder("```sql\n");
        for (CompletionRoutine routine : overloads) body.append(SqlSignatureResolver.routineLabel(routine)).append('\n');
        return body.append("```").toString();
    }

    static String type(ColumnMetadata column) {
        StringBuilder type = new StringBuilder(Objects.toString(column.typeName(), ""));
        if (column.columnSize() > 0) {
            type.append('(').append(column.columnSize());
            if (column.decimalDigits() > 0) type.append(',').append(column.decimalDigits());
            type.append(')');
        }
        return type.toString();
    }

    private static String attributes(ColumnMetadata column) {
        List<String> attributes = new ArrayList<>();
        if (column.isPrimaryKeyPart()) attributes.add("PK");
        if (!column.nullable()) attributes.add("NOT NULL");
        if (column.isAutoIncrement()) attributes.add("AUTO INCREMENT");
        if (column.defaultValue() != null && !column.defaultValue().isBlank())
            attributes.add("DEFAULT " + column.defaultValue().trim());
        return String.join(", ", attributes);
    }

    private static String display(String schema, String name) {
        return schema + "." + name;
    }

    private static String backtick(String value) {
        return "`" + code(value) + "`";
    }

    private static String code(String value) {
        return Objects.toString(value, "").replace("`", "\\`");
    }

    private static String text(String value) {
        return Objects.toString(value, "").replace("|", "\\|").replace('`', '\'').replace('\n', ' ').replace('\r', ' ');
    }
}
