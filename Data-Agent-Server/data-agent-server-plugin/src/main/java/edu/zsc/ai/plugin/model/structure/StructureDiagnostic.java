package edu.zsc.ai.plugin.model.structure;

import java.util.List;

/**
 * A structure-editor finding. {@code code} is stable; the client translates it.
 * Arguments are names or values, never sentences.
 */
public record StructureDiagnostic(String code, List<String> args) {

    public static final String TABLE_NAME_REQUIRED = "TABLE_NAME_REQUIRED";
    public static final String COLUMN_NAME_EMPTY = "COLUMN_NAME_EMPTY";
    public static final String DUPLICATE_COLUMN_NAME = "DUPLICATE_COLUMN_NAME";
    public static final String COLUMN_NOT_IN_BASELINE = "COLUMN_NOT_IN_BASELINE";
    public static final String DUPLICATE_ORIGINAL_COLUMN = "DUPLICATE_ORIGINAL_COLUMN";
    public static final String TABLE_NEEDS_COLUMN = "TABLE_NEEDS_COLUMN";
    public static final String GENERATED_COLUMN_LOCKED = "GENERATED_COLUMN_LOCKED";
    public static final String GENERATED_COLUMN_NOT_EDITABLE = "GENERATED_COLUMN_NOT_EDITABLE";
    public static final String EXPRESSION_INDEX_LOCKED = "EXPRESSION_INDEX_LOCKED";
    public static final String INDEX_HAS_NO_COLUMNS = "INDEX_HAS_NO_COLUMNS";
    public static final String INDEX_MISSING_COLUMN = "INDEX_MISSING_COLUMN";
    public static final String INDEX_NAME_NOT_PLAIN = "INDEX_NAME_NOT_PLAIN";
    public static final String DROPPED_COLUMN_STILL_INDEXED = "DROPPED_COLUMN_STILL_INDEXED";
    public static final String PRIMARY_KEY_MISSING_COLUMN = "PRIMARY_KEY_MISSING_COLUMN";
    public static final String FOREIGN_KEY_INCOMPLETE = "FOREIGN_KEY_INCOMPLETE";
    public static final String CHECK_HAS_SEMICOLON = "CHECK_HAS_SEMICOLON";
    public static final String AUTO_INCREMENT_NEEDS_INDEX = "AUTO_INCREMENT_NEEDS_INDEX";
    public static final String OPTION_MISSING = "OPTION_MISSING";
    public static final String OPTION_UNSUPPORTED = "OPTION_UNSUPPORTED";
    public static final String DM_CANNOT_REORDER = "DM_CANNOT_REORDER";
    public static final String DM_CANNOT_TOGGLE_IDENTITY = "DM_CANNOT_TOGGLE_IDENTITY";
    public static final String DM_INDEX_PREFIX = "DM_INDEX_PREFIX";
    public static final String DM_IGNORES_TABLE_OPTIONS = "DM_IGNORES_TABLE_OPTIONS";
    public static final String DEFAULT_EXPRESSION_SKIPPED = "DEFAULT_EXPRESSION_SKIPPED";
    public static final String STRUCTURE_STALE = "STRUCTURE_STALE";

    public StructureDiagnostic {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static StructureDiagnostic of(String code, String... args) {
        return new StructureDiagnostic(code, List.of(args));
    }
}
