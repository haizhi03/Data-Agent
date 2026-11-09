package edu.zsc.ai.plugin.dm.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmSqlTemplateTest {

    @Test
    void tableDdlUsesDbmsMetadataWithTwoParameters() {
        assertEquals("SELECT DBMS_METADATA.GET_DDL('TABLE', ?, ?) FROM DUAL",
                DmSqlTemplate.SQL_GET_TABLE_DDL);
    }

    @Test
    void viewDdlUsesDbmsMetadataWithTwoParameters() {
        assertEquals("SELECT DBMS_METADATA.GET_DDL('VIEW', ?, ?) FROM DUAL",
                DmSqlTemplate.SQL_GET_VIEW_DDL);
    }

    @Test
    void dropStatementsFormatFullName() {
        assertEquals("DROP TABLE \"HR\".\"users\"",
                String.format(DmSqlTemplate.SQL_DROP_TABLE, "\"HR\".\"users\""));
        assertEquals("DROP VIEW \"HR\".\"v_users\"",
                String.format(DmSqlTemplate.SQL_DROP_VIEW, "\"HR\".\"v_users\""));
    }

    @Test
    void commentQueriesUseOracleCompatibleDictionary() {
        assertTrue(DmSqlTemplate.SQL_TABLE_COMMENT.contains("ALL_TAB_COMMENTS"));
        assertTrue(DmSqlTemplate.SQL_TABLE_COMMENT.contains("TABLE_TYPE = 'TABLE'"));
        assertTrue(DmSqlTemplate.SQL_TABLE_COMMENTS.contains("SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS"));
        assertTrue(DmSqlTemplate.SQL_TABLE_COMMENTS.contains("OWNER = ?"));
        assertTrue(DmSqlTemplate.SQL_TABLE_COMMENTS.contains("TABLE_TYPE = 'TABLE'"));
        assertTrue(DmSqlTemplate.SQL_COLUMN_COMMENTS.contains("ALL_COL_COMMENTS"));
        assertTrue(DmSqlTemplate.SQL_COLUMN_COMMENTS.contains("COLUMN_NAME"));
    }

    @Test
    void commentOnStatementsFormatCorrectly() {
        assertEquals("COMMENT ON TABLE \"HR\".\"users\" IS 'core users'",
                String.format(DmSqlTemplate.SQL_COMMENT_ON_TABLE, "\"HR\".\"users\"", "core users"));
        assertEquals("COMMENT ON COLUMN \"HR\".\"users\".\"name\" IS 'user name'",
                String.format(DmSqlTemplate.SQL_COMMENT_ON_COLUMN, "\"HR\".\"users\"", "\"name\"", "user name"));
    }

    @Test
    void selectTableDataUsesDmLimitPagination() {
        assertEquals("SELECT * FROM \"HR\".\"users\" LIMIT 0, 50",
                String.format(DmSqlTemplate.SQL_SELECT_TABLE_DATA, "\"HR\".\"users\"", 0, 50));
        assertEquals("SELECT COUNT(*) AS total FROM \"HR\".\"users\"",
                String.format(DmSqlTemplate.SQL_COUNT_TABLE_DATA, "\"HR\".\"users\""));
    }

    @Test
    void currentUserQuery() {
        assertEquals("SELECT USER FROM DUAL", DmSqlTemplate.SQL_CURRENT_USER);
    }
}
