package edu.zsc.ai.plugin.model.structure;

import java.util.List;

/** DDL that would bring the baseline structure in line with the draft. */
public record StructurePreview(
        List<String> statements,
        List<Change> changes,
        List<String> warnings
) {
    /**
     * @param objectType TABLE, COLUMN, PRIMARY_KEY, INDEX, FOREIGN_KEY, CHECK
     * @param action     ADD, DROP, MODIFY, RENAME
     */
    public record Change(
            String objectType,
            String action,
            String name,
            boolean destructive,
            String detail
    ) {
    }
}
