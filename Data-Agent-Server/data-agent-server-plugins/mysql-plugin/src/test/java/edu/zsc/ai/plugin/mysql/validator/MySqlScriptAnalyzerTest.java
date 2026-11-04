package edu.zsc.ai.plugin.mysql.validator;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MySqlScriptAnalyzerTest {
    private final MySqlScriptAnalyzer analyzer = MySqlScriptAnalyzer.INSTANCE;

    @Test
    void keepsEscapedStringsIdentifiersAndCommentsTogether() {
        String sql = "SELECT 'it\\'s;a'; SELECT `a;b` FROM t; # ignore;\nSELECT 2;";
        var result = analyzer.analyze(sql);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(List.of("SELECT 'it\\'s;a'", "SELECT `a;b` FROM t", "SELECT 2"), analyzer.split(sql));
    }

    @Test
    void neverSplitsAnInternalDeleteOutOfAProcedure() {
        String procedure = "CREATE PROCEDURE p() BEGIN INSERT INTO t VALUES(1); DELETE FROM t; END";
        String sql = procedure + "; SELECT 2;";
        var result = analyzer.analyze(sql);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(List.of(procedure, "SELECT 2"), analyzer.split(sql));
        assertEquals("CREATE", result.statements().get(0).type().name());
        assertTrue(result.statements().get(0).endOffset() > sql.indexOf("DELETE"));
    }

    @Test
    void returnsEditorOffsetsInUtf16() {
        String sql = "SELECT '😀';\nSELECT 2;";
        var result = analyzer.analyze(sql);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        var statement = result.statements().get(1);
        assertEquals(sql.indexOf("SELECT 2"), statement.startOffset());
        assertEquals("SELECT 2", sql.substring(statement.startOffset(), statement.endOffset()));
    }

    @Test
    void keepsInvalidProcedureAsOneUnitInsteadOfExecutingRecoveredFragments() {
        String sql = "CREATE PROCEDURE p() BEGIN bad syntax; DELETE FROM t; END;";
        assertFalse(analyzer.analyze(sql).errors().isEmpty());
        assertEquals(List.of(sql), analyzer.split(sql));
    }
}
