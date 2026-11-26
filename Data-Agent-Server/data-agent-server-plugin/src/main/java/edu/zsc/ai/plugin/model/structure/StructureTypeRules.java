package edu.zsc.ai.plugin.model.structure;

import java.util.List;
import java.util.Locale;

/**
 * What the structure form may edit for one database dialect.
 * Type names are lowercase prefixes. Matching stops at a letter boundary,
 * so {@code int} does not accept {@code integer}.
 */
public record StructureTypeRules(
        boolean engine,
        boolean charset,
        boolean collation,
        boolean unsigned,
        List<String> lengthTypes,
        List<String> scaleTypes,
        List<String> autoIncrementTypes,
        List<String> numericTypes
) {
    public static final StructureTypeRules MYSQL = new StructureTypeRules(
            true,
            true,
            true,
            true,
            List.of("char", "varchar", "binary", "varbinary", "decimal", "numeric", "float", "double", "bit", "year"),
            List.of("decimal", "numeric"),
            List.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint"),
            List.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "float", "double", "real", "year")
    );

    public static final StructureTypeRules DM = new StructureTypeRules(
            false,
            false,
            false,
            false,
            List.of("char", "character", "varchar", "varchar2", "binary", "varbinary", "number", "numeric", "decimal", "dec", "float", "double"),
            List.of("number", "numeric", "decimal", "dec"),
            List.of("int", "integer", "bigint", "tinyint", "smallint"),
            List.of("number", "numeric", "decimal", "dec", "integer", "int", "bigint", "tinyint", "smallint", "float", "double", "real")
    );

    public StructureTypeRules {
        lengthTypes = copy(lengthTypes);
        scaleTypes = copy(scaleTypes);
        autoIncrementTypes = copy(autoIncrementTypes);
        numericTypes = copy(numericTypes);
    }

    public boolean allowsLength(String typeName) {
        return allows(lengthTypes, typeName);
    }

    public boolean allowsScale(String typeName) {
        return allows(scaleTypes, typeName);
    }

    public boolean allowsAutoIncrement(String typeName) {
        return allows(autoIncrementTypes, typeName);
    }

    public boolean numericDefault(String typeName) {
        return allows(numericTypes, typeName);
    }

    public boolean allows(List<String> types, String typeName) {
        String normalized = normalize(typeName);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String type : types) {
            if (normalized.equals(type)) {
                return true;
            }
            if (normalized.startsWith(type) && boundary(normalized.charAt(type.length()))) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String typeName) {
        if (typeName == null) {
            return "";
        }
        String value = typeName.toLowerCase(Locale.ROOT).trim();
        int paren = value.indexOf('(');
        if (paren >= 0) {
            value = value.substring(0, paren);
        }
        return value.trim();
    }

    private static boolean boundary(char next) {
        return !Character.isLetter(next);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
