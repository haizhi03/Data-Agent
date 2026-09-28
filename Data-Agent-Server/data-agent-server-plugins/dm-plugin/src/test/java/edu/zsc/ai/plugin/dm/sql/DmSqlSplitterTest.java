package edu.zsc.ai.plugin.dm.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmSqlSplitterTest {

    @Test
    void splitsPlainStatementsOnSemicolon() {
        List<String> stmts = DmSqlSplitter.INSTANCE.split("SELECT 1;\nSELECT 2;");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 1", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
    }

    @Test
    void keepsSemicolonInsideStringLiteral() {
        List<String> stmts = DmSqlSplitter.INSTANCE.split("SELECT 'a;b' FROM DUAL; SELECT 2;");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 'a;b' FROM DUAL", stmts.get(0));
    }

    @Test
    void keepsSemicolonInsideQuotedIdentifier() {
        List<String> stmts = DmSqlSplitter.INSTANCE.split("SELECT \"a;b\" FROM T;");
        assertEquals(List.of("SELECT \"a;b\" FROM T"), stmts);
    }

    @Test
    void keepsSemicolonInsideComments() {
        List<String> stmts = DmSqlSplitter.INSTANCE.split(
                "SELECT 1 /* ; */ FROM DUAL; -- ; comment\nSELECT 2;");
        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).contains("/* ; */"));
        assertTrue(stmts.get(1).contains("SELECT 2"));
    }

    @Test
    void plsqlCreateProcedureBlockWithSlashTerminatorIsSingleStatement() {
        String sql = "CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;\n/\nSELECT 1;\nSELECT 2;";
        List<String> stmts = DmSqlSplitter.INSTANCE.split(sql);
        assertEquals(3, stmts.size());
        assertTrue(stmts.get(0).startsWith("CREATE OR REPLACE PROCEDURE P"));
        assertTrue(stmts.get(0).contains("NULL;"));
        assertEquals("SELECT 1", stmts.get(1));
        assertEquals("SELECT 2", stmts.get(2));
    }

    @Test
    void plsqlTriggerBlockWithoutSlashRunsToEnd() {
        String sql = "CREATE TRIGGER TRG BEFORE INSERT ON T BEGIN NULL; END;";
        List<String> stmts = DmSqlSplitter.INSTANCE.split(sql);
        assertEquals(1, stmts.size());
        assertTrue(stmts.get(0).contains("NULL;"));
    }

    @Test
    void createTableIsNotTreatedAsPlsqlBlock() {
        String sql = "CREATE TABLE T (ID INT); SELECT 1;";
        List<String> stmts = DmSqlSplitter.INSTANCE.split(sql);
        assertEquals(2, stmts.size());
        assertEquals("CREATE TABLE T (ID INT)", stmts.get(0));
    }

    @Test
    void multiplePlsqlBlocksSeparatedBySlash() {
        String sql = "CREATE FUNCTION F RETURN INT AS BEGIN RETURN 1; END;\n/\n"
                + "CREATE PROCEDURE P AS BEGIN NULL; END;\n/\nSELECT 1;";
        List<String> stmts = DmSqlSplitter.INSTANCE.split(sql);
        assertEquals(3, stmts.size());
        assertTrue(stmts.get(0).startsWith("CREATE FUNCTION F"));
        assertTrue(stmts.get(1).startsWith("CREATE PROCEDURE P"));
        assertEquals("SELECT 1", stmts.get(2));
    }

    @Test
    void leadingCommentsDoNotTurnProcedureIntoPlainStatement() {
        String sql = "-- deployment note\n/* another note */\n"
                + "CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;\n/\nSELECT 1;";
        List<String> stmts = DmSqlSplitter.INSTANCE.split(sql);
        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).contains("NULL; END"), stmts.toString());
        assertEquals("SELECT 1", stmts.get(1));
    }

    @Test
    void invalidProcedureBodyIsNotPartiallySplit() {
        String sql = "CREATE PROCEDURE P AS BEGIN\n/a + b;\nNULL; END;\n/\nSELECT 1;";
        List<String> stmts = DmSqlSplitter.INSTANCE.split(sql);
        assertEquals(List.of(sql), stmts);
    }

    @Test
    void blankInputReturnsEmptyList() {
        assertTrue(DmSqlSplitter.INSTANCE.split("  \n ").isEmpty());
    }
}
