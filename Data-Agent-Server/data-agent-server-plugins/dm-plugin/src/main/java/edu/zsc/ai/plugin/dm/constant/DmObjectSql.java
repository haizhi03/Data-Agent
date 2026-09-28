package edu.zsc.ai.plugin.dm.constant;

/**
 * SQL constants for DM advanced object layer (index / function / procedure / trigger).
 *
 * <p>DM has schema but no catalog; dictionary views are Oracle-compatible
 * ({@code ALL_OBJECTS}, {@code ALL_ARGUMENTS}, {@code ALL_TRIGGERS},
 * {@code ALL_INDEXES}, {@code ALL_IND_COLUMNS}, {@code ALL_CONSTRAINTS}).
 * Object DDL is retrieved via {@code DBMS_METADATA.GET_DDL}.
 */
public final class DmObjectSql {

    // --- dbms_metadata object types ---
    public static final String OBJECT_TYPE_FUNCTION = "FUNCTION";
    public static final String OBJECT_TYPE_PROCEDURE = "PROCEDURE";
    public static final String OBJECT_TYPE_TRIGGER = "TRIGGER";
    public static final String OBJECT_TYPE_INDEX = "INDEX";
    public static final String OBJECT_TYPE_SEQUENCE = "SEQUENCE";
    public static final String OBJECT_TYPE_CONSTRAINT = "CONSTRAINT";

    // --- schema resolution fallback ---
    /** Returns the current login user (default schema) when Connection.getSchema() is unavailable. */
    public static final String SQL_CURRENT_SCHEMA = "SELECT USER FROM DUAL";

    // --- ALL_OBJECTS routine queries (params: owner, objectType, [namePattern]) ---
    /** Append SQL_ROUTINES_NAME_CLAUSE (optional) and then SQL_ROUTINES_ORDER_BY. */
    public static final String SQL_LIST_ROUTINES =
            "SELECT OBJECT_NAME FROM ALL_OBJECTS"
                    + " WHERE OWNER = ?"
                    + " AND OBJECT_TYPE = ?";
    public static final String SQL_ROUTINES_NAME_CLAUSE = " AND UPPER(OBJECT_NAME) LIKE UPPER(?)";
    public static final String SQL_ROUTINES_ORDER_BY = " ORDER BY OBJECT_NAME";
    /** Append SQL_ROUTINES_NAME_CLAUSE when a name pattern is present. */
    public static final String SQL_COUNT_ROUTINES =
            "SELECT COUNT(*) AS TOTAL FROM ALL_OBJECTS"
                    + " WHERE OWNER = ?"
                    + " AND OBJECT_TYPE = ?";

    // --- ALL_ARGUMENTS (params: owner; %s = IN clause of quoted object-name literals) ---
    /** POSITION = 0 is the function return value in Oracle-compatible dictionaries, so it is excluded. */
    public static final String SQL_FETCH_ARGUMENTS =
            "SELECT OBJECT_NAME, ARGUMENT_NAME, DATA_TYPE, POSITION"
                    + " FROM ALL_ARGUMENTS"
                    + " WHERE OWNER = ?"
                    + " AND PACKAGE_NAME IS NULL"
                    + " AND OBJECT_NAME IN (%s)"
                    + " AND POSITION > 0"
                    + " ORDER BY OBJECT_NAME, POSITION";

    // --- ALL_TRIGGERS (params: owner, [tableName]) ---
    /** Append SQL_TRIGGER_TABLE_CLAUSE (optional) and then SQL_TRIGGERS_ORDER_BY. */
    public static final String SQL_LIST_TRIGGERS =
            "SELECT TRIGGER_NAME, TABLE_NAME, TRIGGERING_TYPE, TRIGGERING_EVENT, STATUS"
                    + " FROM ALL_TRIGGERS"
                    + " WHERE OWNER = ?";
    public static final String SQL_TRIGGER_TABLE_CLAUSE = " AND TABLE_NAME = ?";
    public static final String SQL_TRIGGERS_ORDER_BY = " ORDER BY TRIGGER_NAME";

    // --- ALL_INDEXES (params: tableOwner, tableName) ---
    /** IS_PRIMARY = 1 when the index backs a primary-key constraint (ALL_CONSTRAINTS.INDEX_NAME). */
    public static final String SQL_LIST_INDEXES =
            "SELECT i.INDEX_NAME, i.INDEX_TYPE, i.UNIQUENESS,"
                    + " CASE WHEN c.CONSTRAINT_NAME IS NOT NULL THEN 1 ELSE 0 END AS IS_PRIMARY"
                    + " FROM ALL_INDEXES i"
                    + " LEFT JOIN ALL_CONSTRAINTS c"
                    + " ON c.OWNER = i.OWNER AND c.INDEX_NAME = i.INDEX_NAME AND c.CONSTRAINT_TYPE = 'P'"
                    + " WHERE i.TABLE_OWNER = ?"
                    + " AND i.TABLE_NAME = ?"
                    + " ORDER BY i.INDEX_NAME";

    // --- ALL_IND_COLUMNS (params: indexOwner, tableName) ---
    public static final String SQL_LIST_INDEX_COLUMNS =
            "SELECT INDEX_NAME, COLUMN_NAME, COLUMN_POSITION"
                    + " FROM ALL_IND_COLUMNS"
                    + " WHERE INDEX_OWNER = ?"
                    + " AND TABLE_NAME = ?"
                    + " ORDER BY INDEX_NAME, COLUMN_POSITION";

    // --- ALL_SEQUENCES: inspect definitions without advancing the sequence ---
    public static final String SQL_LIST_SEQUENCES =
            "SELECT SEQUENCE_NAME, MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG, CACHE_SIZE"
                    + " FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = ? ORDER BY SEQUENCE_NAME";

    // Match referenced key columns by POSITION, not by name or row order.
    public static final String SQL_LIST_CONSTRAINTS =
            "SELECT c.CONSTRAINT_NAME, c.CONSTRAINT_TYPE, c.TABLE_NAME, c.STATUS,"
                    + " c.SEARCH_CONDITION, c.R_OWNER, r.TABLE_NAME AS REF_TABLE_NAME,"
                    + " cc.COLUMN_NAME, cc.POSITION, rc.COLUMN_NAME AS REF_COLUMN_NAME"
                    + " FROM ALL_CONSTRAINTS c"
                    + " LEFT JOIN ALL_CONS_COLUMNS cc ON cc.OWNER = c.OWNER"
                    + " AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME"
                    + " LEFT JOIN ALL_CONSTRAINTS r ON r.OWNER = c.R_OWNER"
                    + " AND r.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME"
                    + " LEFT JOIN ALL_CONS_COLUMNS rc ON rc.OWNER = r.OWNER"
                    + " AND rc.CONSTRAINT_NAME = r.CONSTRAINT_NAME AND rc.POSITION = cc.POSITION"
                    + " WHERE c.OWNER = ? AND c.TABLE_NAME = ?"
                    + " ORDER BY c.CONSTRAINT_NAME, cc.POSITION";

    // --- dbms_metadata (%s = escaped literals: object type, object name, owner schema) ---
    public static final String SQL_GET_OBJECT_DDL =
            "SELECT DBMS_METADATA.GET_DDL('%s', '%s', '%s') AS DDL FROM DUAL";

    // --- DROP statements (%s = full quoted identifier "SCHEMA"."NAME") ---
    public static final String SQL_DROP_FUNCTION = "DROP FUNCTION %s";
    public static final String SQL_DROP_PROCEDURE = "DROP PROCEDURE %s";
    public static final String SQL_DROP_TRIGGER = "DROP TRIGGER %s";
    public static final String SQL_DROP_SEQUENCE = "DROP SEQUENCE %s";
    public static final String SQL_DROP_CONSTRAINT = "ALTER TABLE %s DROP CONSTRAINT %s";

    private DmObjectSql() {
    }
}
