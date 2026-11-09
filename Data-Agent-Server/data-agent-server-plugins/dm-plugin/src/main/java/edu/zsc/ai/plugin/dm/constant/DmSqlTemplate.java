package edu.zsc.ai.plugin.dm.constant;

/**
 * All SQL strings used by the DM (达梦) plugin metadata layer.
 * DM is Oracle-dictionary compatible: ALL_TABLES / ALL_VIEWS / ALL_TAB_COLUMNS /
 * ALL_TAB_COMMENTS / ALL_COL_COMMENTS, and DBMS_METADATA.GET_DDL for DDL extraction.
 */
public final class DmSqlTemplate {

    // --- DBMS_METADATA DDL extraction ---
    /** Parameters: 1 = exact table name, 2 = exact owner schema. */
    public static final String SQL_GET_TABLE_DDL =
            "SELECT DBMS_METADATA.GET_DDL('TABLE', ?, ?) FROM DUAL";
    /** Parameters: 1 = exact view name, 2 = exact owner schema. */
    public static final String SQL_GET_VIEW_DDL =
            "SELECT DBMS_METADATA.GET_DDL('VIEW', ?, ?) FROM DUAL";

    // --- DROP commands ---
    /** %s = full table name ("SCHEMA"."TABLE" or TABLE, escaped and quoted) */
    public static final String SQL_DROP_TABLE = "DROP TABLE %s";
    /** %s = full view name ("SCHEMA"."VIEW" or VIEW, escaped and quoted) */
    public static final String SQL_DROP_VIEW = "DROP VIEW %s";

    // --- RENAME commands ---
    /** %1$s = full table name ("SCHEMA"."TABLE"), %2$s = quoted new table name */
    public static final String SQL_RENAME_TABLE = "ALTER TABLE %s RENAME TO %s";

    // --- Comments (Oracle compatible dictionary views) ---
    /** Parameters: 1 = exact owner schema, 2 = exact table name. */
    public static final String SQL_TABLE_COMMENT =
            "SELECT COMMENTS FROM ALL_TAB_COMMENTS"
                    + " WHERE OWNER = ? AND TABLE_NAME = ? AND TABLE_TYPE = 'TABLE'";
    /** Parameter: 1 = exact owner schema. Base tables only; views are excluded. */
    public static final String SQL_TABLE_COMMENTS =
            "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS"
                    + " WHERE OWNER = ? AND TABLE_TYPE = 'TABLE'";
    /** Parameters: 1 = exact owner schema, 2 = exact table name. */
    public static final String SQL_COLUMN_COMMENTS =
            "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS"
                    + " WHERE OWNER = ? AND TABLE_NAME = ?";

    // --- Identity columns (parameters: 1 = exact table name, 2 = exact owner schema) ---
    public static final String SQL_AUTO_INCREMENT_COLUMNS =
            "SELECT c.NAME FROM SYS.SYSCOLUMNS c"
                    + " JOIN SYS.SYSOBJECTS o ON c.ID = o.ID"
                    + " WHERE c.INFO2 = 1 AND o.NAME = ?"
                    + " AND o.SCHID = (SELECT s.ID FROM SYS.SYSOBJECTS s"
                    + " WHERE s.NAME = ? AND s.TYPE$ = 'SCH')";

    /** %1$s = full table name, %2$s = escaped comment text */
    public static final String SQL_COMMENT_ON_TABLE = "COMMENT ON TABLE %s IS '%s'";
    /** %1$s = full table name, %2$s = quoted column name, %3$s = escaped comment text */
    public static final String SQL_COMMENT_ON_COLUMN = "COMMENT ON COLUMN %s.%s IS '%s'";

    // --- Table/View data query (DM pagination: LIMIT offset, row_count) ---
    /** %1$s = full table/view name, %2$d = offset, %3$d = page size */
    public static final String SQL_SELECT_TABLE_DATA =
            "SELECT * FROM %s LIMIT %d, %d";
    /** %1$s = full table/view name */
    public static final String SQL_COUNT_TABLE_DATA =
            "SELECT COUNT(*) AS total FROM %s";

    /** Current DM user (default schema) */
    public static final String SQL_CURRENT_USER = "SELECT USER FROM DUAL";

    private DmSqlTemplate() {
    }
}
