package edu.zsc.ai.plugin.mysql.structure;

import edu.zsc.ai.plugin.capability.MysqlIdentifierEscaper;
import edu.zsc.ai.plugin.model.structure.StructureDefaultKind;
import edu.zsc.ai.plugin.model.structure.StructureDiagnostic;
import edu.zsc.ai.plugin.model.structure.StructureReplacementGuard;
import edu.zsc.ai.plugin.model.structure.StructureTypeRules;
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
 * Turns a baseline/draft pair into MySQL ALTER statements.
 * An unchanged draft produces no statements.
 */
public final class MysqlTableStructureDialect {

    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final MysqlIdentifierEscaper ESCAPER = MysqlIdentifierEscaper.getInstance();

    private MysqlTableStructureDialect() {
    }

    public static StructurePreview preview(String catalog, String schema, TableStructure baseline, TableStructure draft) {
        TableStructure base = normalize(baseline);
        TableStructure next = normalize(draft);
        List<StructureDiagnostic> warnings = new ArrayList<>();
        String tableName = StringUtils.defaultIfBlank(base.name(), next.name());
        if (StringUtils.isBlank(tableName)) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.TABLE_NAME_REQUIRED));
            return empty(warnings);
        }
        if (hasBlockers(base, next, warnings)) {
            return empty(warnings);
        }

        List<Column> named = namedColumns(next);
        Map<String, Column> draftByOriginal = new LinkedHashMap<>();
        for (Column column : named) {
            if (StringUtils.isNotBlank(column.originalName())) {
                draftByOriginal.put(column.originalName(), column);
            }
        }

        List<Change> changes = new ArrayList<>();
        List<String> dropFk = new ArrayList<>();
        List<String> addFk = new ArrayList<>();
        diffForeignKeys(base, next, changes, dropFk, addFk, warnings);

        List<String> dropChecks = new ArrayList<>();
        List<String> addChecks = new ArrayList<>();
        diffChecks(base, next, changes, dropChecks, addChecks, warnings);

        List<String> dropIndexes = new ArrayList<>();
        List<String> addIndexes = new ArrayList<>();
        diffIndexes(base, next, changes, dropIndexes, addIndexes, warnings);
        List<String> body = new ArrayList<>(dropIndexes);
        diffPrimaryKey(base, next, named, changes, body, warnings);
        diffColumns(base, named, draftByOriginal, changes, body, warnings);
        body.addAll(addIndexes);
        warnAutoIncrement(named, next.primaryKey(), next.indexes(), warnings);

        List<String> statements = new ArrayList<>();
        String table = qualify(catalog, schema, tableName);
        addAlter(statements, table, dropFk);
        addAlter(statements, table, dropChecks);
        addAlter(statements, table, body);
        addAlter(statements, table, addFk);
        addAlter(statements, table, addChecks);
        addTableOptions(statements, table, base, next, changes, warnings);
        return new StructurePreview(List.copyOf(statements), List.copyOf(changes), List.copyOf(warnings));
    }

    private static boolean hasBlockers(TableStructure base, TableStructure next, List<StructureDiagnostic> warnings) {
        boolean blocked = false;
        List<String> names = new ArrayList<>();
        for (Column column : next.columns()) {
            boolean existing = StringUtils.isNotBlank(column.originalName());
            if (StringUtils.isBlank(column.name())) {
                if (existing) {
                    warnings.add(StructureDiagnostic.of(StructureDiagnostic.COLUMN_NAME_EMPTY, StringUtils.defaultString(column.originalName())));
                    blocked = true;
                }
                continue;
            }
            if (names.stream().anyMatch(name -> name.equalsIgnoreCase(column.name()))) {
                warnings.add(StructureDiagnostic.of(StructureDiagnostic.DUPLICATE_COLUMN_NAME, column.name()));
                blocked = true;
            }
            names.add(column.name());
            if (existing && base.columns().stream().noneMatch(item -> column.originalName().equals(item.name()))) {
                warnings.add(StructureDiagnostic.of(StructureDiagnostic.COLUMN_NOT_IN_BASELINE, StringUtils.defaultString(column.originalName())));
                blocked = true;
            }
        }
        if (names.isEmpty() && !base.columns().isEmpty()) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.TABLE_NEEDS_COLUMN));
            blocked = true;
        }
        long distinctOriginals = next.columns().stream()
                .map(Column::originalName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .count();
        long originalCount = next.columns().stream().map(Column::originalName).filter(StringUtils::isNotBlank).count();
        if (distinctOriginals != originalCount) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.DUPLICATE_ORIGINAL_COLUMN));
            blocked = true;
        }
        for (Column loaded : base.columns()) {
            if (loaded.generationExpression() == null) {
                continue;
            }
            Column draft = next.columns().stream()
                    .filter(column -> loaded.name().equals(column.originalName()))
                    .findFirst()
                    .orElse(null);
            if (draft == null || !loaded.name().equals(draft.name()) || !sameStructure(loaded, draft)) {
                warnings.add(StructureDiagnostic.of(StructureDiagnostic.GENERATED_COLUMN_LOCKED, loaded.name()));
                blocked = true;
                continue;
            }
            if (StringUtils.isBlank(loaded.generationExpression()) && !blankEquals(loaded.comment(), draft.comment())) {
                warnings.add(StructureDiagnostic.of(StructureDiagnostic.GENERATED_COLUMN_NOT_EDITABLE, loaded.name()));
                blocked = true;
            }
        }
        for (Index index : base.indexes()) {
            if (!expressionIndex(index)) {
                continue;
            }
            Index draft = next.indexes().stream()
                    .filter(item -> index.name().equals(item.originalName()))
                    .findFirst()
                    .orElse(null);
            if (draft == null || !sameIndex(index, draft)) {
                warnings.add(StructureDiagnostic.of(StructureDiagnostic.EXPRESSION_INDEX_LOCKED, index.name()));
                blocked = true;
            }
        }
        if (StructureReplacementGuard.block(base, next, false, warnings)) {
            blocked = true;
        }
        return blocked;
    }

    private static void diffColumns(TableStructure base, List<Column> named, Map<String, Column> draftByOriginal,
                                    List<Change> changes, List<String> body, List<StructureDiagnostic> warnings) {
        for (Column column : base.columns()) {
            if (!draftByOriginal.containsKey(column.name())) {
                changes.add(new Change("COLUMN", "DROP", column.name(), true, null));
                body.add("DROP COLUMN " + quote(column.name()));
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
                body.add("ADD COLUMN " + columnDefinition(column) + position(previous));
                previous = column.name();
                continue;
            }
            Column loaded = base.columns().stream().filter(item -> original.equals(item.name())).findFirst().orElse(null);
            if (loaded == null) {
                continue;
            }
            int index = live.indexOf(original);
            String livePrevious = index <= 0 ? null : live.get(index - 1);
            boolean renamed = !original.equals(column.name());
            boolean definitionChanged = !sameDefinition(loaded, column);
            boolean positionChanged = !Objects.equals(livePrevious, previous);
            if (renamed || definitionChanged || positionChanged) {
                boolean destructive = !sameStructure(loaded, column);
                String action = renamed && !definitionChanged && !positionChanged ? "RENAME" : "MODIFY";
                String detail = renamed ? original + " -> " + column.name() : column.columnType();
                changes.add(new Change("COLUMN", action, column.name(), destructive, detail));
                String verb = renamed ? "CHANGE COLUMN " + quote(original) + " " : "MODIFY COLUMN ";
                body.add(verb + columnDefinition(column) + position(previous));
                live.remove(original);
                int insertAt = previous == null ? 0 : live.indexOf(previous) + 1;
                live.add(Math.max(insertAt, 0), column.name());
            } else {
                live.set(index, column.name());
            }
            previous = column.name();
        }

        for (Column column : base.columns()) {
            if (draftByOriginal.containsKey(column.name())) {
                continue;
            }
            for (Index index : base.indexes()) {
                if (index.columns().stream().anyMatch(column.name()::equals)) {
                    warnings.add(StructureDiagnostic.of(StructureDiagnostic.DROPPED_COLUMN_STILL_INDEXED, column.name(), index.name()));
                }
            }
        }
    }

    private static void diffPrimaryKey(TableStructure base, TableStructure next, List<Column> named,
                                       List<Change> changes, List<String> body, List<StructureDiagnostic> warnings) {
        List<String> before = keyColumns(base.primaryKey());
        List<String> after = keyColumns(next.primaryKey());
        if (before.equals(after)) {
            return;
        }
        if (!after.isEmpty() && after.stream().anyMatch(name -> named.stream().noneMatch(column -> name.equals(column.name())))) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.PRIMARY_KEY_MISSING_COLUMN));
            return;
        }
        if (before.isEmpty()) {
            changes.add(new Change("PRIMARY_KEY", "ADD", "PRIMARY", false, String.join(", ", after)));
            body.add("ADD PRIMARY KEY (" + quoteList(after) + ")");
            return;
        }
        if (after.isEmpty()) {
            changes.add(new Change("PRIMARY_KEY", "DROP", "PRIMARY", true, String.join(", ", before)));
            body.add("DROP PRIMARY KEY");
            return;
        }
        changes.add(new Change("PRIMARY_KEY", "MODIFY", "PRIMARY", true,
                String.join(", ", before) + " -> " + String.join(", ", after)));
        body.add("DROP PRIMARY KEY");
        body.add("ADD PRIMARY KEY (" + quoteList(after) + ")");
    }

    private static void diffIndexes(TableStructure base, TableStructure next, List<Change> changes,
                                    List<String> drop, List<String> add, List<StructureDiagnostic> warnings) {
        Map<String, Index> draftByOriginal = originals(next.indexes());
        for (Index index : base.indexes()) {
            Index draft = draftByOriginal.get(index.name());
            if (draft == null) {
                changes.add(new Change("INDEX", "DROP", index.name(), true, null));
                drop.add("DROP INDEX " + quote(index.name()));
            } else if (!sameIndex(index, draft)) {
                drop.add("DROP INDEX " + quote(index.name()));
            }
        }
        for (Index index : next.indexes()) {
            if (index.columns() == null || index.columns().isEmpty()) {
                Index loaded = StringUtils.isBlank(index.originalName()) ? null : findIndex(base, index.originalName());
                boolean alreadyEmpty = loaded != null && (loaded.columns() == null || loaded.columns().isEmpty());
                if (!alreadyEmpty && (StringUtils.isNotBlank(index.name()) || StringUtils.isNotBlank(index.originalName()))) {
                    warnings.add(StructureDiagnostic.of(StructureDiagnostic.INDEX_HAS_NO_COLUMNS, StringUtils.defaultIfBlank(index.name(), index.originalName())));
                }
                continue;
            }
            Index loaded = StringUtils.isBlank(index.originalName()) ? null : findIndex(base, index.originalName());
            if (loaded != null && sameIndex(loaded, index)) {
                continue;
            }
            String name = StringUtils.defaultIfBlank(index.name(), generatedName("idx", next.name(), index.columns().get(0)));
            if (!SIMPLE_NAME.matcher(name).matches() && StringUtils.isBlank(index.name())) {
                warnings.add(StructureDiagnostic.of(StructureDiagnostic.INDEX_NAME_NOT_PLAIN));
                continue;
            }
            changes.add(new Change("INDEX", loaded == null ? "ADD" : "MODIFY", name, loaded != null,
                    String.join(", ", index.columns())));
            add.add("ADD " + indexKind(index) + " " + quote(name) + " (" + indexColumns(index) + ")" + using(index));
        }
    }

    private static void diffForeignKeys(TableStructure base, TableStructure next, List<Change> changes,
                                        List<String> drop, List<String> add, List<StructureDiagnostic> warnings) {
        Map<String, ForeignKey> draftByOriginal = new LinkedHashMap<>();
        for (ForeignKey foreignKey : next.foreignKeys()) {
            if (StringUtils.isNotBlank(foreignKey.originalName())) {
                draftByOriginal.put(foreignKey.originalName(), foreignKey);
            }
        }
        for (ForeignKey foreignKey : base.foreignKeys()) {
            ForeignKey draft = draftByOriginal.get(foreignKey.name());
            if (draft == null) {
                changes.add(new Change("FOREIGN_KEY", "DROP", foreignKey.name(), true, null));
                drop.add("DROP FOREIGN KEY " + quote(foreignKey.name()));
            } else if (!sameForeignKey(foreignKey, draft)) {
                drop.add("DROP FOREIGN KEY " + quote(foreignKey.name()));
            }
        }
        for (ForeignKey foreignKey : next.foreignKeys()) {
            if (foreignKey.columns().isEmpty() || StringUtils.isBlank(foreignKey.referencedTable())
                    || foreignKey.referencedColumns().isEmpty()) {
                if (StringUtils.isNotBlank(foreignKey.name()) || StringUtils.isNotBlank(foreignKey.originalName())) {
                    warnings.add(StructureDiagnostic.of(StructureDiagnostic.FOREIGN_KEY_INCOMPLETE, StringUtils.defaultIfBlank(foreignKey.name(), foreignKey.originalName())));
                }
                continue;
            }
            ForeignKey loaded = StringUtils.isBlank(foreignKey.originalName()) ? null : base.foreignKeys().stream()
                    .filter(item -> foreignKey.originalName().equals(item.name())).findFirst().orElse(null);
            if (loaded != null && sameForeignKey(loaded, foreignKey)) {
                continue;
            }
            String name = StringUtils.defaultIfBlank(foreignKey.name(),
                    generatedName("fk", next.name(), foreignKey.columns().get(0)));
            changes.add(new Change("FOREIGN_KEY", loaded == null ? "ADD" : "MODIFY", name, loaded != null,
                    foreignKey.referencedTable()));
            add.add("ADD CONSTRAINT " + quote(name) + " FOREIGN KEY (" + quoteList(foreignKey.columns())
                    + ") REFERENCES " + qualify(foreignKey.referencedSchema(), null, foreignKey.referencedTable())
                    + " (" + quoteList(foreignKey.referencedColumns()) + ")"
                    + referentialAction("DELETE", foreignKey.onDelete())
                    + referentialAction("UPDATE", foreignKey.onUpdate()));
        }
    }

    private static void diffChecks(TableStructure base, TableStructure next, List<Change> changes,
                                   List<String> drop, List<String> add, List<StructureDiagnostic> warnings) {
        Map<String, Check> draftByOriginal = new LinkedHashMap<>();
        for (Check check : next.checks()) {
            if (StringUtils.isNotBlank(check.originalName())) {
                draftByOriginal.put(check.originalName(), check);
            }
        }
        for (Check check : base.checks()) {
            Check draft = draftByOriginal.get(check.name());
            if (draft == null) {
                changes.add(new Change("CHECK", "DROP", check.name(), true, null));
                drop.add("DROP CHECK " + quote(check.name()));
            } else if (!sameCheck(check, draft)) {
                drop.add("DROP CHECK " + quote(check.name()));
            }
        }
        for (Check check : next.checks()) {
            if (StringUtils.isBlank(check.expression())) {
                continue;
            }
            if (check.expression().indexOf(';') >= 0) {
                warnings.add(StructureDiagnostic.of(StructureDiagnostic.CHECK_HAS_SEMICOLON, check.name()));
                continue;
            }
            Check loaded = StringUtils.isBlank(check.originalName()) ? null : base.checks().stream()
                    .filter(item -> check.originalName().equals(item.name())).findFirst().orElse(null);
            if (loaded != null && sameCheck(loaded, check)) {
                continue;
            }
            String name = StringUtils.defaultIfBlank(check.name(), generatedName("chk", next.name(), "c"));
            changes.add(new Change("CHECK", loaded == null ? "ADD" : "MODIFY", name, loaded != null, check.expression()));
            String expression = check.expression().trim();
            if (!expression.startsWith("(")) {
                expression = "(" + expression + ")";
            }
            add.add("ADD CONSTRAINT " + quote(name) + " CHECK " + expression);
        }
    }

    private static void addTableOptions(List<String> statements, String table, TableStructure base, TableStructure next,
                                        List<Change> changes, List<StructureDiagnostic> warnings) {
        List<String> clauses = new ArrayList<>();
        boolean engineChanged = !equalsIgnoreCase(base.engine(), next.engine());
        boolean charsetChanged = !equalsIgnoreCase(base.charset(), next.charset());
        boolean collationChanged = !equalsIgnoreCase(base.collation(), next.collation());
        boolean commentChanged = !blankEquals(base.comment(), next.comment());
        if (engineChanged && acceptSimpleName(next.engine(), "engine", warnings)) {
            clauses.add("ENGINE=" + next.engine().trim());
        }
        if (charsetChanged && acceptSimpleName(next.charset(), "charset", warnings)) {
            clauses.add("DEFAULT CHARACTER SET " + next.charset().trim());
        }
        if ((charsetChanged || collationChanged) && acceptSimpleName(next.collation(), "collation", warnings)) {
            clauses.add("COLLATE " + next.collation().trim());
        }
        if (commentChanged) {
            clauses.add("COMMENT='" + ESCAPER.escapeStringLiteral(StringUtils.defaultString(next.comment())) + "'");
        }
        if (clauses.isEmpty()) {
            return;
        }
        boolean destructive = engineChanged || charsetChanged || collationChanged;
        String detail = commentChanged && !destructive ? "comment" : String.join(", ", clauses);
        changes.add(new Change("TABLE", "MODIFY", next.name(), destructive, detail));
        statements.add("ALTER TABLE " + table + " " + String.join(", ", clauses));
    }

    private static void warnAutoIncrement(List<Column> columns, PrimaryKey primaryKey, List<Index> indexes,
                                          List<StructureDiagnostic> warnings) {
        List<String> keyed = new ArrayList<>(keyColumns(primaryKey));
        for (Index index : indexes) {
            keyed.addAll(index.columns());
        }
        for (Column column : columns) {
            if (column.autoIncrement() && keyed.stream().noneMatch(name -> name.equalsIgnoreCase(column.name()))) {
                warnings.add(StructureDiagnostic.of(StructureDiagnostic.AUTO_INCREMENT_NEEDS_INDEX, column.name()));
            }
        }
    }

    private static void addAlter(List<String> statements, String table, List<String> clauses) {
        if (!clauses.isEmpty()) {
            statements.add("ALTER TABLE " + table + "\n  " + String.join(",\n  ", clauses));
        }
    }

    private static String columnDefinition(Column column) {
        StringBuilder sql = new StringBuilder();
        sql.append(quote(column.name())).append(' ').append(resolvedType(column));
        if (StringUtils.isNotBlank(column.charset()) && SIMPLE_NAME.matcher(column.charset().trim()).matches()) {
            sql.append(" CHARACTER SET ").append(column.charset().trim());
        }
        if (StringUtils.isNotBlank(column.collation()) && SIMPLE_NAME.matcher(column.collation().trim()).matches()) {
            sql.append(" COLLATE ").append(column.collation().trim());
        }
        boolean generated = column.generationExpression() != null;
        if (generated && StringUtils.isNotBlank(column.generationExpression())) {
            String expression = column.generationExpression().trim();
            if (!expression.startsWith("(")) {
                expression = "(" + expression + ")";
            }
            sql.append(" GENERATED ALWAYS AS ").append(expression);
            sql.append(column.generationStored() ? " STORED" : " VIRTUAL");
        }
        sql.append(column.nullable() ? " NULL" : " NOT NULL");
        if (!generated) {
            sql.append(defaultClause(column));
            if (column.autoIncrement()) {
                sql.append(" AUTO_INCREMENT");
            }
            if (StringUtils.isNotBlank(column.onUpdate()) && column.onUpdate().indexOf(';') < 0) {
                sql.append(" ON UPDATE ").append(column.onUpdate().trim());
            }
        }
        if (StringUtils.isNotEmpty(column.comment())) {
            sql.append(" COMMENT '").append(ESCAPER.escapeStringLiteral(column.comment())).append("'");
        }
        return sql.toString();
    }

    private static String resolvedType(Column column) {
        if (StringUtils.isNotBlank(column.columnType())) {
            return column.columnType().trim();
        }
        return StringUtils.defaultIfBlank(column.typeName(), "varchar(255)");
    }

    private static String defaultClause(Column column) {
        String kind = StringUtils.defaultIfBlank(column.defaultKind(), StructureDefaultKind.NONE);
        return switch (kind) {
            case StructureDefaultKind.NULL -> " DEFAULT NULL";
            case StructureDefaultKind.EXPRESSION -> expressionDefault(column.defaultValue());
            case StructureDefaultKind.VALUE -> valueDefault(column);
            default -> "";
        };
    }

    private static String expressionDefault(String expression) {
        if (StringUtils.isBlank(expression) || expression.indexOf(';') >= 0) {
            return "";
        }
        return " DEFAULT " + expression.trim();
    }

    private static String valueDefault(Column column) {
        String value = column.defaultValue();
        if (value == null) {
            return "";
        }
        if (unquotedDefault(column, value)) {
            return " DEFAULT " + value;
        }
        return " DEFAULT '" + ESCAPER.escapeStringLiteral(value) + "'";
    }

    private static boolean unquotedDefault(Column column, String value) {
        String type = StringUtils.defaultIfBlank(column.typeName(), column.columnType()).toLowerCase(Locale.ROOT);
        if (type.startsWith("bool")) {
            return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) || NUMERIC_LITERAL.matcher(value).matches();
        }
        if (type.startsWith("bit")) {
            return NUMERIC_LITERAL.matcher(value).matches() || value.toLowerCase(Locale.ROOT).startsWith("b'");
        }
        return StructureTypeRules.MYSQL.numericDefault(type) && NUMERIC_LITERAL.matcher(value).matches();
    }

    private static String position(String previous) {
        return previous == null ? " FIRST" : " AFTER " + quote(previous);
    }

    private static String indexKind(Index index) {
        if ("FULLTEXT".equalsIgnoreCase(index.type())) {
            return "FULLTEXT INDEX";
        }
        if ("SPATIAL".equalsIgnoreCase(index.type())) {
            return "SPATIAL INDEX";
        }
        return index.unique() ? "UNIQUE INDEX" : "INDEX";
    }

    private static String using(Index index) {
        if (StringUtils.isBlank(index.type()) || "FULLTEXT".equalsIgnoreCase(index.type())
                || "SPATIAL".equalsIgnoreCase(index.type()) || "BTREE".equalsIgnoreCase(index.type())) {
            return "";
        }
        if (!SIMPLE_NAME.matcher(index.type().trim()).matches()) {
            return "";
        }
        return " USING " + index.type().trim();
    }

    private static String indexColumns(Index index) {
        List<Integer> prefixes = alignPrefixes(index);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < index.columns().size(); i++) {
            String part = quote(index.columns().get(i));
            if (prefixes.get(i) != null) {
                part += "(" + prefixes.get(i) + ")";
            }
            parts.add(part);
        }
        return String.join(", ", parts);
    }

    private static String referentialAction(String event, String rule) {
        if (StringUtils.isBlank(rule) || "RESTRICT".equalsIgnoreCase(rule) || "NO ACTION".equalsIgnoreCase(rule)) {
            return "";
        }
        String normalized = rule.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("CASCADE") && !normalized.equals("SET NULL") && !normalized.equals("SET DEFAULT")) {
            return "";
        }
        return " ON " + event + " " + normalized;
    }

    private static boolean sameDefinition(Column left, Column right) {
        return sameStructure(left, right) && blankEquals(left.comment(), right.comment());
    }

    /** Type, nullability, default and auto-increment. Comment and name are ignored. */
    private static boolean sameStructure(Column left, Column right) {
        return equalsIgnoreCase(resolvedType(left), resolvedType(right))
                && equalsIgnoreCase(left.charset(), right.charset())
                && equalsIgnoreCase(left.collation(), right.collation())
                && left.nullable() == right.nullable()
                && left.autoIncrement() == right.autoIncrement()
                && blankEquals(normalizeKind(left.defaultKind()), normalizeKind(right.defaultKind()))
                && blankEquals(left.defaultValue(), right.defaultValue())
                && equalsIgnoreCase(left.onUpdate(), right.onUpdate())
                && Objects.equals(left.generationExpression(), right.generationExpression())
                && left.generationStored() == right.generationStored();
    }

    private static boolean expressionIndex(Index index) {
        return index.columns() == null || index.columns().stream().anyMatch(StringUtils::isBlank);
    }

    private static boolean sameIndex(Index left, Index right) {
        return left.unique() == right.unique()
                && equalsIgnoreCase(StringUtils.defaultIfBlank(left.type(), "BTREE"), StringUtils.defaultIfBlank(right.type(), "BTREE"))
                && left.columns().equals(right.columns())
                && alignPrefixes(left).equals(alignPrefixes(right))
                && blankEquals(left.name(), StringUtils.defaultIfBlank(right.name(), left.name()));
    }

    private static boolean sameForeignKey(ForeignKey left, ForeignKey right) {
        return left.columns().equals(right.columns())
                && equalsIgnoreCase(left.referencedSchema(), right.referencedSchema())
                && left.referencedTable().equals(right.referencedTable())
                && left.referencedColumns().equals(right.referencedColumns())
                && sameRule(left.onUpdate(), right.onUpdate())
                && sameRule(left.onDelete(), right.onDelete())
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

    private static List<Integer> alignPrefixes(Index index) {
        List<Integer> raw = index.prefixLengths() == null ? List.of() : index.prefixLengths();
        List<Integer> aligned = new ArrayList<>();
        for (int i = 0; i < index.columns().size(); i++) {
            Integer value = i < raw.size() ? raw.get(i) : null;
            aligned.add(value != null && value > 0 ? value : null);
        }
        return aligned;
    }

    private static List<String> keyColumns(PrimaryKey primaryKey) {
        if (primaryKey == null || primaryKey.columns() == null) {
            return List.of();
        }
        return primaryKey.columns().stream().filter(StringUtils::isNotBlank).toList();
    }

    private static Map<String, Index> originals(List<Index> indexes) {
        Map<String, Index> mapped = new LinkedHashMap<>();
        for (Index index : indexes) {
            if (StringUtils.isNotBlank(index.originalName())) {
                mapped.put(index.originalName(), index);
            }
        }
        return mapped;
    }

    private static Index findIndex(TableStructure structure, String name) {
        return structure.indexes().stream().filter(index -> name.equals(index.name())).findFirst().orElse(null);
    }

    private static List<Column> namedColumns(TableStructure structure) {
        return structure.columns().stream().filter(column -> StringUtils.isNotBlank(column.name())).toList();
    }

    private static String generatedName(String prefix, String table, String column) {
        String raw = prefix + "_" + StringUtils.defaultString(table) + "_" + StringUtils.defaultString(column);
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private static boolean acceptSimpleName(String value, String label, List<StructureDiagnostic> warnings) {
        if (StringUtils.isBlank(value)) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.OPTION_MISSING, label));
            return false;
        }
        if (!SIMPLE_NAME.matcher(value.trim()).matches()) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.OPTION_UNSUPPORTED, label, value));
            return false;
        }
        return true;
    }

    private static String qualify(String catalog, String schema, String name) {
        String database = StringUtils.isNotBlank(catalog) ? catalog : schema;
        if (StringUtils.isBlank(database)) {
            return quote(name);
        }
        return quote(database) + "." + quote(name);
    }

    private static String quoteList(List<String> names) {
        return names.stream().map(MysqlTableStructureDialect::quote).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return StringUtils.defaultString(left).trim().equalsIgnoreCase(StringUtils.defaultString(right).trim());
    }

    private static boolean blankEquals(String left, String right) {
        return StringUtils.defaultString(left).equals(StringUtils.defaultString(right));
    }

    private static StructurePreview empty(List<StructureDiagnostic> warnings) {
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
                cleanIndexes(structure.indexes()),
                structure.foreignKeys() == null ? List.of() : structure.foreignKeys(),
                structure.checks() == null ? List.of() : structure.checks()
        );
    }

    private static List<Index> cleanIndexes(List<Index> indexes) {
        if (indexes == null) {
            return List.of();
        }
        return indexes.stream()
                .filter(index -> index != null && !"PRIMARY".equalsIgnoreCase(index.name()))
                .map(index -> new Index(
                        index.name(),
                        index.originalName(),
                        index.unique(),
                        index.type(),
                        index.columns() == null ? List.of() : index.columns(),
                        index.prefixLengths()
                ))
                .toList();
    }
}
