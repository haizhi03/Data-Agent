package edu.zsc.ai.plugin.model.structure;

/** How a column default is stored in a table-structure draft. */
public final class StructureDefaultKind {

    public static final String NONE = "NONE";
    public static final String NULL = "NULL";
    public static final String VALUE = "VALUE";
    public static final String EXPRESSION = "EXPRESSION";

    private StructureDefaultKind() {
    }
}
