package edu.zsc.ai.plugin.model.structure;

import edu.zsc.ai.plugin.model.structure.TableStructure.Check;
import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import edu.zsc.ai.plugin.model.structure.TableStructure.ForeignKey;
import edu.zsc.ai.plugin.model.structure.TableStructure.Index;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Rejects a whole structure plan when an existing index, foreign key, or check
 * would be dropped and its replacement cannot be created.
 */
public final class StructureReplacementGuard {

    private StructureReplacementGuard() {
    }

    public static boolean block(TableStructure base, TableStructure next, boolean rejectIndexPrefix,
                                List<StructureDiagnostic> warnings) {
        Set<String> columns = new HashSet<>();
        for (Column column : next.columns()) {
            if (StringUtils.isNotBlank(column.name())) {
                columns.add(column.name());
            }
        }
        boolean blocked = false;
        for (Index draft : next.indexes()) {
            Index loaded = loaded(base.indexes(), draft.originalName(), Index::name);
            if (loaded != null && invalidIndex(loaded, draft, columns, rejectIndexPrefix, warnings)) {
                blocked = true;
            }
        }
        for (ForeignKey draft : next.foreignKeys()) {
            ForeignKey loaded = loaded(base.foreignKeys(), draft.originalName(), ForeignKey::name);
            if (loaded != null && invalidForeignKey(draft, columns, warnings)) {
                blocked = true;
            }
        }
        for (Check draft : next.checks()) {
            Check loaded = loaded(base.checks(), draft.originalName(), Check::name);
            if (loaded != null && invalidCheck(draft, warnings)) {
                blocked = true;
            }
        }
        return blocked;
    }

    private static boolean invalidIndex(Index loaded, Index draft, Set<String> columns, boolean rejectIndexPrefix,
                                        List<StructureDiagnostic> warnings) {
        boolean loadedEmpty = loaded.columns() == null || loaded.columns().isEmpty();
        boolean draftEmpty = draft.columns() == null || draft.columns().isEmpty();
        if (loadedEmpty && draftEmpty) {
            return false;
        }
        String name = StringUtils.defaultIfBlank(draft.name(), draft.originalName());
        if (draftEmpty) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.INDEX_HAS_NO_COLUMNS, name));
            return true;
        }
        if (draft.columns().stream().anyMatch(StringUtils::isBlank)) {
            return false;
        }
        if (draft.columns().stream().anyMatch(column -> StringUtils.isBlank(column) || !columns.contains(column))) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.INDEX_MISSING_COLUMN, name));
            return true;
        }
        boolean prefixed = draft.prefixLengths() != null
                && draft.prefixLengths().stream().anyMatch(prefix -> prefix != null && prefix > 0);
        if (rejectIndexPrefix && prefixed) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.DM_INDEX_PREFIX, name));
            return true;
        }
        return false;
    }

    private static boolean invalidForeignKey(ForeignKey draft, Set<String> columns, List<StructureDiagnostic> warnings) {
        boolean incomplete = draft.columns() == null || draft.columns().isEmpty()
                || StringUtils.isBlank(draft.referencedTable())
                || draft.referencedColumns() == null || draft.referencedColumns().isEmpty()
                || draft.columns().stream().anyMatch(column -> StringUtils.isBlank(column) || !columns.contains(column));
        if (!incomplete) {
            return false;
        }
        warnings.add(StructureDiagnostic.of(StructureDiagnostic.FOREIGN_KEY_INCOMPLETE,
                StringUtils.defaultIfBlank(draft.name(), draft.originalName())));
        return true;
    }

    private static boolean invalidCheck(Check draft, List<StructureDiagnostic> warnings) {
        String name = StringUtils.defaultIfBlank(draft.name(), draft.originalName());
        if (StringUtils.isBlank(draft.expression())) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.CHECK_EXPRESSION_EMPTY, name));
            return true;
        }
        if (draft.expression().indexOf(';') >= 0) {
            warnings.add(StructureDiagnostic.of(StructureDiagnostic.CHECK_HAS_SEMICOLON, name));
            return true;
        }
        return false;
    }

    private static <T> T loaded(List<T> items, String originalName, Function<T, String> nameOf) {
        if (StringUtils.isBlank(originalName) || items == null) {
            return null;
        }
        for (T item : items) {
            if (originalName.equals(nameOf.apply(item))) {
                return item;
            }
        }
        return null;
    }
}
