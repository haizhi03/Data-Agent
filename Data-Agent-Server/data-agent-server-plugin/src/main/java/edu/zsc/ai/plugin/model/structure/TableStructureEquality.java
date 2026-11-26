package edu.zsc.ai.plugin.model.structure;

import edu.zsc.ai.plugin.model.structure.TableStructure.Check;
import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import edu.zsc.ai.plugin.model.structure.TableStructure.ForeignKey;
import edu.zsc.ai.plugin.model.structure.TableStructure.Index;
import edu.zsc.ai.plugin.model.structure.TableStructure.PrimaryKey;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Compares two loaded snapshots. Blank text matches null so a JSON round-trip
 * does not look like an external edit.
 */
public final class TableStructureEquality {

    private TableStructureEquality() {
    }

    public static boolean same(TableStructure left, TableStructure right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return textEquals(left.name(), right.name())
                && textEquals(left.comment(), right.comment())
                && textEquals(left.engine(), right.engine())
                && textEquals(left.charset(), right.charset())
                && textEquals(left.collation(), right.collation())
                && columnsEqual(left.columns(), right.columns())
                && primaryEqual(left.primaryKey(), right.primaryKey())
                && indexesEqual(left.indexes(), right.indexes())
                && foreignKeysEqual(left.foreignKeys(), right.foreignKeys())
                && checksEqual(left.checks(), right.checks());
    }

    private static boolean columnsEqual(List<Column> left, List<Column> right) {
        if (size(left) != size(right)) {
            return false;
        }
        for (int i = 0; i < size(left); i++) {
            Column a = left.get(i);
            Column b = right.get(i);
            if (!textEquals(a.name(), b.name())
                    || !textEquals(a.originalName(), b.originalName())
                    || !textEquals(a.typeName(), b.typeName())
                    || !textEquals(a.columnType(), b.columnType())
                    || !Objects.equals(a.length(), b.length())
                    || !Objects.equals(a.scale(), b.scale())
                    || a.nullable() != b.nullable()
                    || a.autoIncrement() != b.autoIncrement()
                    || a.unsigned() != b.unsigned()
                    || !textEquals(normalizeKind(a.defaultKind()), normalizeKind(b.defaultKind()))
                    || !textEquals(a.defaultValue(), b.defaultValue())
                    || !textEquals(a.onUpdate(), b.onUpdate())
                    || !textEquals(a.comment(), b.comment())
                    || !textEquals(a.charset(), b.charset())
                    || !textEquals(a.collation(), b.collation())
                    || !textEquals(a.generationExpression(), b.generationExpression())
                    || a.generationStored() != b.generationStored()) {
                return false;
            }
        }
        return true;
    }

    private static boolean primaryEqual(PrimaryKey left, PrimaryKey right) {
        if (left == null || right == null) {
            return left == right;
        }
        return textEquals(left.name(), right.name()) && textsEqual(left.columns(), right.columns());
    }

    private static boolean indexesEqual(List<Index> left, List<Index> right) {
        if (size(left) != size(right)) {
            return false;
        }
        for (int i = 0; i < size(left); i++) {
            Index a = left.get(i);
            Index b = right.get(i);
            if (!textEquals(a.name(), b.name())
                    || !textEquals(a.originalName(), b.originalName())
                    || a.unique() != b.unique()
                    || !textEquals(a.type(), b.type())
                    || !textsEqual(a.columns(), b.columns())
                    || !integersEqual(a.prefixLengths(), b.prefixLengths())) {
                return false;
            }
        }
        return true;
    }

    private static boolean foreignKeysEqual(List<ForeignKey> left, List<ForeignKey> right) {
        if (size(left) != size(right)) {
            return false;
        }
        for (int i = 0; i < size(left); i++) {
            ForeignKey a = left.get(i);
            ForeignKey b = right.get(i);
            if (!textEquals(a.name(), b.name())
                    || !textEquals(a.originalName(), b.originalName())
                    || !textsEqual(a.columns(), b.columns())
                    || !textEquals(a.referencedSchema(), b.referencedSchema())
                    || !textEquals(a.referencedTable(), b.referencedTable())
                    || !textsEqual(a.referencedColumns(), b.referencedColumns())
                    || !textEquals(a.onUpdate(), b.onUpdate())
                    || !textEquals(a.onDelete(), b.onDelete())) {
                return false;
            }
        }
        return true;
    }

    private static boolean checksEqual(List<Check> left, List<Check> right) {
        if (size(left) != size(right)) {
            return false;
        }
        for (int i = 0; i < size(left); i++) {
            Check a = left.get(i);
            Check b = right.get(i);
            if (!textEquals(a.name(), b.name())
                    || !textEquals(a.originalName(), b.originalName())
                    || !textEquals(a.expression(), b.expression())) {
                return false;
            }
        }
        return true;
    }

    private static boolean textsEqual(List<String> left, List<String> right) {
        if (size(left) != size(right)) {
            return false;
        }
        for (int i = 0; i < size(left); i++) {
            if (!textEquals(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean integersEqual(List<Integer> left, List<Integer> right) {
        if (size(left) != size(right)) {
            return false;
        }
        for (int i = 0; i < size(left); i++) {
            if (!Objects.equals(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean textEquals(String left, String right) {
        return Objects.equals(text(left), text(right));
    }

    private static String text(String value) {
        return StringUtils.trimToNull(value);
    }

    private static String normalizeKind(String kind) {
        return StringUtils.defaultIfBlank(kind, StructureDefaultKind.NONE).trim().toUpperCase();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
