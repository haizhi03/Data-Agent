package edu.zsc.ai.plugin.dm.structure;

import edu.zsc.ai.plugin.dm.util.DmIdentifierEscaper;
import edu.zsc.ai.plugin.dm.value.DmDataTypeEnum;
import edu.zsc.ai.plugin.model.structure.StructureDefaultKind;
import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.StructurePreview.Change;
import edu.zsc.ai.plugin.model.structure.TableStructure;
import edu.zsc.ai.plugin.model.structure.TableStructure.Check;
import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import edu.zsc.ai.plugin.model.structure.TableStructure.ForeignKey;
import edu.zsc.ai.plugin.model.structure.TableStructure.Index;
import edu.zsc.ai.plugin.model.structure.TableStructure.PrimaryKey;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Renders a structure draft as DM statements.
 * Indexes and comments are separate statements. Column order is not changed.
 * Drops run before column changes. New keys and indexes run after the columns they use.
 * An unchanged draft produces no statements.
 */
public final class DmTableStructureDialect {

    private static final Pattern NUMERIC_LITERAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final DmIdentifierEscaper ESCAPER = DmIdentifierEscaper.getInstance();

    private DmTableStructureDialect() {
    }

    public static StructurePreview preview(String catalog, String schema, TableStructure baseline, TableStructure draft) {
        TableStructure base = normalize(baseline);
        TableStructure next = normalize(draft);
        List<String> warnings = new ArrayList<>();
        String tableName = StringUtils.defaultIfBlank(base.name(), next.name());
        if (StringUtils.isBlank(tableName)) {
            warnings.add("Table name is required");
            return empty(warnings);
        }
        warnIgnoredTableOptions(base, next, warnings);
        if (hasBlockers(base, next, warnings)) {
            return empty(warnings);
        }

        String owner = StringUtils.firstNonBlank(schema, catalog);
        String table = qualify(owner, tableName);
        List<Column> named = namedColumns(next);
        Map<String, Column> draftByOriginal = new LinkedHashMap<>();
        for (Column column : named) {
            if (StringUtils.isNotBlank(column.originalName())) {
                draftByOriginal.put(column.originalName(), column);
            }
        }

        List<Change> changes = new ArrayList<>();
        List<String> dropForeignKeys = new ArrayList<>();
        List<String> addForeignKeys = new ArrayList<>();
        diffForeignKeys(owner, table, base, next, changes, dropForeignKeys, addForeignKeys, warnings);
        List<String> dropChecks = new ArrayList<>();
        List<String> addChecks = new ArrayList<>();
        diffChecks(table, base, next, changes, dropChecks, addChecks, warnings);
        List<String> dropIndexes = new ArrayList<>();
        List<String> addIndexes = new ArrayList<>();
        diffIndexes(owner, table, base, next, named, changes, dropIndexes, addIndexes, warnings);
        List<String> dropPrimaryKey = new ArrayList<>();
        List<String> addPrimaryKey = new ArrayList<>();
        diffPrimaryKey(table, base, next, named, changes, dropPrimaryKey, addPrimaryKey, warnings);
        List<String> columnStatements = new ArrayList<>();
        diffColumns(owner, table, base, named, draftByOriginal, changes, columnStatements, warnings);

        List<String> statements = new ArrayList<>();
        statements.addAll(dropForeignKeys);
        statements.addAll(dropChecks);
        statements.addAll(dropIndexes);
        statements.addAll(dropPrimaryKey);
        statements.addAll(columnStatements);
        statements.addAll(addPrimaryKey);
        statements.addAll(addIndexes);
        statements.addAll(addChecks);
        statements.addAll(addForeignKeys);
        diffTableComment(table, base, next, changes, statements);
        return new StructurePreview(List.copyOf(statements), List.copyOf(changes), List.copyOf(warnings));
    }

    private static void diffColumns(String owner, String table, TableStructure base, List<Column> named,
                                    Map<String, Column> draftByOriginal, List<Change> changes,
                                    List<String> statements, List<String> warnings) {
        for (Column column : base.columns()) {
            if (!draftByOriginal.containsKey(column.name())) {
                changes.add(new Change("COLUMN", "DROP", column.name(), true, null));
                statements.add("ALTER TABLE " + table + " DROP COLUMN " + quote(column.name()));
            }
        }

        List<String> live = new ArrayList<>();
        for (Column column : base.columns()) {
            if (draftByOriginal.containsKey(column.name())) {
                live.add(column.name());
            }
        }
        String previous = null;
        for (Column column : named) {
            String original = StringUtils.trimToNull(column.originalName());
            if (original == null) {
                changes.add(new Change("COLUMN", "ADD", column.name(), false, column.columnType()));
                statements.add("ALTER TABLE " + table + " ADD " + columnDefinition(column, warnings));
                if (StringUtils.isNotEmpty(column.comment())) {
                    statements.add(columnComment(owner, base.name(), column));
                }
                previous = column.name();
                continue;
            }
            Column loaded = findColumn(base, original);
            if (loaded == null) {
                continue;
            }
            int index = live.indexOf(original);
            String livePrevious = index <= 0 ? null : live.get(index - 1);
            boolean renamed = !original.equals(column.name());
            boolean definitionChanged = !sameStructure(loaded, column);
            boolean commentChanged = !blankEquals(loaded.comment(), column.comment());
            boolean positionChanged = !Objects.equals(livePrevious, previous);
            if (positionChanged) {
                warnings.add("DM cannot reorder columns: " + column.name());
            }
            if (loaded.autoIncrement() != column.autoIncrement()) {
                warnings.add("DM cannot toggle IDENTITY on an existing column: " + column.name());
            }
            if (renamed) {
                changes.add(new Change("COLUMN", definitionChanged || commentChanged ? "MODIFY" : "RENAME",
                        column.name(), definitionChanged, original + " -> " + column.name()));
                statements.add("ALTER TABLE " + table + " RENAME COLUMN " + quote(original) + " TO " + quote(column.name()));
            }
            if (definitionChanged) {
                if (!renamed) {
                    changes.add(new Change("COLUMN", "MODIFY", column.name(), true, column.columnType()));
                }
                statements.add("ALTER TABLE " + table + " MODIFY " + columnDefinition(column, warnings));
            }
            if (commentChanged) {
                if (!renamed && !definitionChanged) {
                    changes.add(new Change("COLUMN", "MODIFY", column.name(), false, "comment"));
                }
                statements.add(columnComment(owner, base.name(), column));
            }
            previous = column.name();
        }
    }

    private static void diffPrimaryKey(String table, TableStructure base, TableStructure next, List<Column> named,
                                       List<Change> changes, List<String> drops, List<String> adds, List<String> warnings) {
        List<String> before = keyColumns(base.primaryKey());
        List<String> after = keyColumns(next.primaryKey());
        if (before.equals(after)) {
            return;
        }
        if (!after.isEmpty() && after.stream().anyMatch(name -> named.stream().noneMatch(column -> name.equals(column.name())))) {
            warnings.add("Primary key references a missing column");
            return;
        }
        String constraint = StringUtils.firstNonBlank(
                base.primaryKey() == null ? null : base.primaryKey().name(),
                next.primaryKey() == null ? null : next.primaryKey().name(),
                generatedName("PK", base.name(), "ID"));
        if (!before.isEmpty()) {
            changes.add(new Change("PRIMARY_KEY", after.isEmpty() ? "DROP" : "MODIFY", constraint, true,
                    String.join(", ", before)));
            drops.add("ALTER TABLE " + table + " DROP CONSTRAINT " + quote(constraint));
        }
        if (!after.isEmpty()) {
            if (before.isEmpty()) {
                changes.add(new Change("PRIMARY_KEY", "ADD", constraint, false, String.join(", ", after)));
            }
            adds.add("ALTER TABLE " + table + " ADD CONSTRAINT " + quote(constraint)
                    + " PRIMARY KEY (" + quoteList(after) + ")");
        }
    }

    private static void diffIndexes(String owner, String table, TableStructure base, TableStructure next,
                                    List<Column> named, List<Change> changes, List<String> drops, List<String> adds,
                                    List<String> warnings) {
        Map<String, Index> draftByOriginal = new LinkedHashMap<>();
        for (Index index : next.indexes()) {
            if (StringUtils.isNotBlank(index.originalName())) {
                draftByOriginal.put(index.originalName(), index);
            }
        }
        for (Index index : base.indexes()) {
            Index draft = draftByOriginal.get(index.name());
            if (draft == null || !sameIndex(index, draft)) {
                if (draft == null) {
                    changes.add(new Change("INDEX", "DROP", index.name(), true, null));
                }
                drops.add("DROP INDEX " + qualify(owner, index.name()));
            }
        }
        for (Index index : next.indexes()) {
            if (index.columns() == null || index.columns().isEmpty()) {
                Index loaded = StringUtils.isBlank(index.originalName()) ? null : findIndex(base, index.originalName());
                boolean alreadyEmpty = loaded != null && (loaded.columns() == null || loaded.columns().isEmpty());
                if (!alreadyEmpty && (StringUtils.isNotBlank(index.name()) || StringUtils.isNotBlank(index.originalName()))) {
                    warnings.add("Index has no columns: " + StringUtils.defaultIfBlank(index.name(), index.originalName()));
                }
                continue;
            }
            Index loaded = StringUtils.isBlank(index.originalName()) ? null : findIndex(base, index.originalName());
            boolean prefixed = index.prefixLengths() != null && index.prefixLengths().stream().anyMatch(prefix -> prefix != null && prefix > 0);
            if (loaded != null && sameIndex(loaded, index)) {
                if (prefixed) {
                    warnings.add("DM indexes do not keep a MySQL-style prefix length: " + index.name());
                }
                continue;
            }
            if (prefixed) {
                warnings.add("DM indexes do not keep a MySQL-style prefix length: " + StringUtils.defaultIfBlank(index.name(), index.originalName()));
                continue;
            }
            if (index.columns().stream().anyMatch(column -> named.stream().noneMatch(item -> column.equals(item.name())))) {
                warnings.add("Index references a missing column: " + StringUtils.defaultIfBlank(index.name(), index.originalName()));
                continue;
            }
            String name = StringUtils.defaultIfBlank(index.name(), generatedName("IDX", next.name(), index.columns().get(0)));
            changes.add(new Change("INDEX", loaded == null ? "ADD" : "MODIFY", name, loaded != null,
                    String.join(", ", index.columns())));
            adds.add("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + qualify(owner, name)
                    + " ON " + table + " (" + quoteList(index.columns()) + ")");
        }
    }

    private static void diffForeignKeys(String owner, String table, TableStructure base, TableStructure next,
                                        List<Change> changes, List<String> drops, List<String> adds, List<String> warnings) {
        Map<String, ForeignKey> draftByOriginal = new LinkedHashMap<>();
        for (ForeignKey foreignKey : next.foreignKeys()) {
            if (StringUtils.isNotBlank(foreignKey.originalName())) {
                draftByOriginal.put(foreignKey.originalName(), foreignKey);
            }
        }
        for (ForeignKey foreignKey : base.foreignKeys()) {
            ForeignKey draft = draftByOriginal.get(foreignKey.name());
            if (draft == null || !sameForeignKey(foreignKey, draft)) {
                if (draft == null) {
                    changes.add(new Change("FOREIGN_KEY", "DROP", foreignKey.name(), true, null));
                }
                drops.add("ALTER TABLE " + table + " DROP CONSTRAINT " + quote(foreignKey.name()));
            }
        }
        for (ForeignKey foreignKey : next.foreignKeys()) {
            if (foreignKey.columns().isEmpty() || StringUtils.isBlank(foreignKey.referencedTable())
                    || foreignKey.referencedColumns().isEmpty()) {
                if (StringUtils.isNotBlank(foreignKey.name()) || StringUtils.isNotBlank(foreignKey.originalName())) {
                    warnings.add("Foreign key is incomplete: " + StringUtils.defaultIfBlank(foreignKey.name(), foreignKey.originalName()));
                }
                continue;
            }
            ForeignKey loaded = StringUtils.isBlank(foreignKey.originalName()) ? null
                    : base.foreignKeys().stream().filter(item -> foreignKey.originalName().equals(item.name())).findFirst().orElse(null);
            if (loaded != null && sameForeignKey(loaded, foreignKey)) {
                continue;
            }
            String name = StringUtils.defaultIfBlank(foreignKey.name(), generatedName("FK", next.name(), foreignKey.columns().get(0)));
            changes.add(new Change("FOREIGN_KEY", loaded == null ? "ADD" : "MODIFY", name, loaded != null,
                    foreignKey.referencedTable()));
            String referencedOwner = StringUtils.firstNonBlank(foreignKey.referencedSchema(), owner);
            adds.add("ALTER TABLE " + table + " ADD CONSTRAINT " + quote(name)
                    + " FOREIGN KEY (" + quoteList(foreignKey.columns()) + ") REFERENCES "
                    + qualify(referencedOwner, foreignKey.referencedTable())
                    + " (" + quoteList(foreignKey.referencedColumns()) + ")"
                    + referentialAction("DELETE", foreignKey.onDelete())
                    + referentialAction("UPDATE", foreignKey.onUpdate()));
        }
    }

    private static void diffChecks(String table, TableStructure base, TableStructure next,
                                   List<Change> changes, List<String> drops, List<String> adds, List<String> warnings) {
        Map<String, Check> draftByOriginal = new LinkedHashMap<>();
        for (Check check : next.checks()) {
            if (StringUtils.isNotBlank(check.originalName())) {
                draftByOriginal.put(check.originalName(), check);
            }
        }
        for (Check check : base.checks()) {
            Check draft = draftByOriginal.get(check.name());
            if (draft == null || !sameCheck(check, draft)) {
                if (draft == null) {
                    changes.add(new Change("CHECK", "DROP", check.name(), true, null));
                }
                drops.add("ALTER TABLE " + table + " DROP CONSTRAINT " + quote(check.name()));
            }
        }
        for (Check check : next.checks()) {
            if (StringUtils.isBlank(check.expression())) {
                continue;
            }
            if (check.expression().indexOf(';') >= 0) {
                warnings.add("Check expression cannot contain a semicolon: " + check.name());
                continue;
            }
            Check loaded = StringUtils.isBlank(check.originalName()) ? null
                    : base.checks().stream().filter(item -> check.originalName().equals(item.name())).findFirst().orElse(null);
            if (loaded != null && sameCheck(loaded, check)) {
                continue;
            }
            String name = StringUtils.defaultIfBlank(check.name(), generatedName("CHK", next.name(), "C"));
            changes.add(new Change("CHECK", loaded == null ? "ADD" : "MODIFY", name, loaded != null, check.expression()));
            String expression = check.expression().trim();
            if (!expression.startsWith("(")) {
                expression = "(" + expression + ")";
            }
            adds.add("ALTER TABLE " + table + " ADD CONSTRAINT " + quote(name) + " CHECK " + expression);
        }
    }

    private static void diffTableComment(String table, TableStructure base, TableStructure next,
                                         List<Change> changes, List<String> statements) {
        if (blankEquals(base.comment(), next.comment())) {
            return;
        }
        changes.add(new Change("TABLE", "MODIFY", next.name(), false, "comment"));
        statements.add("COMMENT ON TABLE " + table + " IS '"
                + ESCAPER.escapeStringLiteral(StringUtils.defaultString(next.comment())) + "'");
    }

    private static void warnIgnoredTableOptions(TableStructure base, TableStructure next, List<String> warnings) {
        if (!equalsIgnoreCase(base.engine(), next.engine())
                || !equalsIgnoreCase(base.charset(), next.charset())
                || !equalsIgnoreCase(base.collation(), next.collation())) {
            warnings.add("DM does not apply storage engine, character set, or collation");
        }
    }

    private static String columnDefinition(Column column, List<String> warnings) {
        StringBuilder sql = new StringBuilder();
        sql.append(quote(column.name())).append(' ');
        sql.append(StringUtils.defaultIfBlank(column.columnType(), StringUtils.defaultIfBlank(column.typeName(), "VARCHAR(255)")));
        if (StringUtils.isBlank(column.originalName()) && column.autoIncrement()) {
            sql.append(" IDENTITY(1,1)");
        }
        sql.append(defaultClause(column, warnings));
        sql.append(column.nullable() ? " NULL" : " NOT NULL");
        return sql.toString();
    }

    private static String defaultClause(Column column, List<String> warnings) {
        String kind = StringUtils.defaultIfBlank(column.defaultKind(), StructureDefaultKind.NONE).trim().toUpperCase(Locale.ROOT);
        return switch (kind) {
            case StructureDefaultKind.NULL -> " DEFAULT NULL";
            case StructureDefaultKind.VALUE -> valueDefault(column);
            case StructureDefaultKind.EXPRESSION -> expressionDefault(column.defaultValue(), warnings);
            default -> "";
        };
    }

    private static String valueDefault(Column column) {
        String value = column.defaultValue();
        if (value == null) {
            return "";
        }
        if (unquotedNumericDefault(column, value)) {
            return " DEFAULT " + value;
        }
        return " DEFAULT '" + ESCAPER.escapeStringLiteral(value) + "'";
    }

    private static boolean unquotedNumericDefault(Column column, String value) {
        if (!NUMERIC_LITERAL.matcher(value).matches()) {
            return false;
        }
        String type = StringUtils.defaultIfBlank(column.typeName(), column.columnType());
        DmDataTypeEnum parsed = DmDataTypeEnum.fromTypeName(type);
        if (parsed == null) {
            return false;
        }
        return switch (parsed) {
            case NUMBER, NUMERIC, DECIMAL, DEC, INTEGER, INT, BIGINT, TINYINT, SMALLINT,
                    FLOAT, DOUBLE, DOUBLE_PRECISION, REAL -> true;
            default -> false;
        };
    }

    private static String expressionDefault(String expression, List<String> warnings) {
        if (StringUtils.isBlank(expression) || expression.indexOf(';') >= 0) {
            warnings.add("Default expression was skipped");
            return "";
        }
        return " DEFAULT " + expression.trim();
    }

    private static String columnComment(String owner, String table, Column column) {
        return "COMMENT ON COLUMN " + qualify(owner, table) + "." + quote(column.name())
                + " IS '" + ESCAPER.escapeStringLiteral(StringUtils.defaultString(column.comment())) + "'";
    }

    private static String referentialAction(String event, String rule) {
        if (StringUtils.isBlank(rule) || "RESTRICT".equalsIgnoreCase(rule) || "NO ACTION".equalsIgnoreCase(rule)) {
            return "";
        }
        String normalized = rule.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("CASCADE") && !normalized.equals("SET NULL")) {
            return "";
        }
        return " ON " + event + " " + normalized;
    }

    private static boolean sameStructure(Column left, Column right) {
        return equalsIgnoreCase(StringUtils.defaultIfBlank(left.columnType(), left.typeName()),
                StringUtils.defaultIfBlank(right.columnType(), right.typeName()))
                && left.nullable() == right.nullable()
                && blankEquals(normalizeKind(left.defaultKind()), normalizeKind(right.defaultKind()))
                && blankEquals(left.defaultValue(), right.defaultValue());
    }

    private static boolean sameIndex(Index left, Index right) {
        return left.unique() == right.unique()
                && left.columns().equals(right.columns())
                && blankEquals(left.name(), StringUtils.defaultIfBlank(right.name(), left.name()));
    }

    private static boolean sameForeignKey(ForeignKey left, ForeignKey right) {
        return left.columns().equals(right.columns())
                && equalsIgnoreCase(left.referencedSchema(), right.referencedSchema())
                && left.referencedTable().equals(right.referencedTable())
                && left.referencedColumns().equals(right.referencedColumns())
                && sameRule(left.onDelete(), right.onDelete())
                && sameRule(left.onUpdate(), right.onUpdate())
                && blankEquals(left.name(), StringUtils.defaultIfBlank(right.name(), left.name()));
    }

    private static boolean sameCheck(Check left, Check right) {
        return blankEquals(unwrap(left.expression()), unwrap(right.expression()))
                && blankEquals(left.name(), StringUtils.defaultIfBlank(right.name(), left.name()));
    }

    private static boolean sameRule(String left, String right) {
        return normalizeRule(left).equals(normalizeRule(right));
    }

    private static String normalizeRule(String rule) {
        if (StringUtils.isBlank(rule) || "NO ACTION".equalsIgnoreCase(rule) || "RESTRICT".equalsIgnoreCase(rule)) {
            return "RESTRICT";
        }
        return rule.trim().toUpperCase(Locale.ROOT);
    }

    private static String unwrap(String expression) {
        String value = StringUtils.trimToEmpty(expression);
        if (value.startsWith("(") && value.endsWith(")") && value.length() > 1) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String normalizeKind(String kind) {
        return StringUtils.defaultIfBlank(kind, StructureDefaultKind.NONE).trim().toUpperCase(Locale.ROOT);
    }

    private static boolean hasBlockers(TableStructure base, TableStructure next, List<String> warnings) {
        boolean blocked = false;
        List<String> names = new ArrayList<>();
        for (Column column : next.columns()) {
            boolean existing = StringUtils.isNotBlank(column.originalName());
            if (StringUtils.isBlank(column.name())) {
                if (existing) {
                    warnings.add("Column name cannot be empty: " + column.originalName());
                    blocked = true;
                }
                continue;
            }
            if (names.stream().anyMatch(name -> name.equalsIgnoreCase(column.name()))) {
                warnings.add("Duplicate column name: " + column.name());
                blocked = true;
            }
            names.add(column.name());
        }
        if (names.isEmpty() && !base.columns().isEmpty()) {
            warnings.add("Table must keep at least one column");
            blocked = true;
        }
        for (Index index : next.indexes()) {
            if (StringUtils.isBlank(index.originalName()) || !index.columns().isEmpty()) {
                continue;
            }
            Index loaded = findIndex(base, index.originalName());
            if (loaded == null || loaded.columns() == null || loaded.columns().isEmpty()) {
                continue;
            }
            warnings.add("Index has no columns: " + StringUtils.defaultIfBlank(index.name(), index.originalName()));
            blocked = true;
        }
        return blocked;
    }

    private static Column findColumn(TableStructure structure, String name) {
        return structure.columns().stream().filter(column -> name.equals(column.name())).findFirst().orElse(null);
    }

    private static Index findIndex(TableStructure structure, String name) {
        return structure.indexes().stream().filter(index -> name.equals(index.name())).findFirst().orElse(null);
    }

    private static List<Column> namedColumns(TableStructure structure) {
        return structure.columns().stream().filter(column -> StringUtils.isNotBlank(column.name())).toList();
    }

    private static List<String> keyColumns(PrimaryKey primaryKey) {
        if (primaryKey == null || primaryKey.columns() == null) {
            return List.of();
        }
        return primaryKey.columns().stream().filter(StringUtils::isNotBlank).toList();
    }

    private static String generatedName(String prefix, String table, String column) {
        String cleaned = (prefix + "_" + StringUtils.defaultString(table) + "_" + StringUtils.defaultString(column))
                .replaceAll("[^A-Za-z0-9_]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private static String qualify(String owner, String name) {
        if (StringUtils.isBlank(owner)) {
            return quote(name);
        }
        return quote(owner) + "." + quote(name);
    }

    private static String quoteList(List<String> names) {
        return names.stream().map(DmTableStructureDialect::quote).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String quote(String identifier) {
        return ESCAPER.quoteIdentifier(identifier);
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return StringUtils.defaultString(left).trim().equalsIgnoreCase(StringUtils.defaultString(right).trim());
    }

    private static boolean blankEquals(String left, String right) {
        return StringUtils.defaultString(left).equals(StringUtils.defaultString(right));
    }

    private static StructurePreview empty(List<String> warnings) {
        return new StructurePreview(List.of(), List.of(), List.copyOf(warnings));
    }

    private static TableStructure normalize(TableStructure structure) {
        if (structure == null) {
            return new TableStructure("", "", null, null, null, List.of(), null, List.of(), List.of(), List.of());
        }
        return new TableStructure(
                StringUtils.defaultString(structure.name()),
                structure.comment(),
                structure.engine(),
                structure.charset(),
                structure.collation(),
                structure.columns() == null ? List.of() : structure.columns(),
                structure.primaryKey(),
                structure.indexes() == null ? List.of() : structure.indexes().stream()
                        .filter(index -> index != null && !"PRIMARY".equalsIgnoreCase(index.name()))
                        .map(index -> new Index(index.name(), index.originalName(), index.unique(), index.type(),
                                index.columns() == null ? List.of() : index.columns(), index.prefixLengths()))
                        .toList(),
                structure.foreignKeys() == null ? List.of() : structure.foreignKeys(),
                structure.checks() == null ? List.of() : structure.checks()
        );
    }
}
