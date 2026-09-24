package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.model.sql.SqlType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmSqlParserTest {

    private final DmSqlParser parser = DmSqlParser.INSTANCE;

    @Test
    void extractsQueryObjectsAndAliases() {
        DmStatement statement = parser.parseExecutableSql("SELECT t.NAME FROM SYSOBJECTS t WHERE t.ID = 1");
        assertEquals(SqlType.SELECT, statement.type());
        assertTrue(statement.readOnly());
        assertEquals(List.of("SYSOBJECTS"), statement.tables());
        assertTrue(statement.columns().contains("t.NAME"));
        assertEquals("SYSOBJECTS", statement.aliases().get("t"));
    }

    @Test
    void rejectsInvalidAndMultiStatementValidation() {
        assertFalse(parser.validate("SELECT FROM T").valid());
        assertFalse(parser.validate("SELECT 1; DELETE FROM T").valid());
    }

    @Test
    void doesNotCertifySideEffectsAsReadOnly() {
        assertEquals(SqlType.UNKNOWN, parser.validate("SELECT S.NEXTVAL FROM DUAL").sqlType());
        assertEquals(SqlType.UNKNOWN, parser.validate("SELECT ID FROM T FOR UPDATE").sqlType());
        assertEquals(SqlType.UNKNOWN, parser.validate("SELECT CUSTOM_FUNC() FROM DUAL").sqlType());
        assertFalse(parser.isSelect("SELECT ID FROM T FOR UPDATE"));
    }

    @Test
    void splitsStatementsByGrammar() {
        assertEquals(List.of("SELECT 1 FROM DUAL", "DELETE FROM T"),
                parser.split("SELECT 1 FROM DUAL; DELETE FROM T;"));
    }

    @Test
    void preservesLeadingCommentsAndOptimizerHintsWhenSplitting() {
        String sql = "/*+ HINT */ SELECT ID FROM T; -- second\nSELECT 2 FROM DUAL;";
        assertEquals(List.of("/*+ HINT */ SELECT ID FROM T", "-- second\nSELECT 2 FROM DUAL"),
                parser.split(sql));
    }

    @Test
    void preservesSupplementaryUnicodeBeforeStatementBoundary() {
        String sql = "SELECT '😀' FROM DUAL; SELECT ID FROM T;";
        assertEquals(List.of("SELECT '😀' FROM DUAL", "SELECT ID FROM T"), parser.split(sql));
        assertEquals(sql.indexOf("SELECT ID"), parser.parserStatements(sql).statements().get(1).startOffset());
    }

    @Test
    void simpleModeClassifiesWithoutMetadataExtraction() {
        DmStatement statement = parser.simpleParserStatements("UPDATE HR.T SET ID = 2").statements().get(0);
        assertEquals(SqlType.UPDATE, statement.type());
        assertTrue(statement.tables().isEmpty());
    }

    @Test
    void extractsDdlTargetForScopedRefresh() {
        DmStatement statement = parser.parseExecutableSql("CREATE TABLE HR.TEST (ID INT)");
        assertEquals(SqlType.CREATE, statement.type());
        assertEquals("TABLE", statement.objectType());
        assertEquals(List.of("HR.TEST"), statement.tables());
    }

    @Test
    void keepsProcedureBodyTogetherAndExcludesSlashDelimiter() {
        String sql = "CREATE PROCEDURE TEST AS BEGIN INSERT INTO T VALUES (1); "
                + "INSERT INTO T VALUES (2); END;\n/\nSELECT 1 FROM DUAL;";
        DmSqlParser.ParseResult result = parser.parserSqlScript(sql);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(2, result.statements().size());
        assertEquals(SqlType.CREATE, result.statements().get(0).type());
        assertTrue(result.statements().get(0).sql().contains("VALUES (2)"));
    }

    @Test
    void reportsLineAndColumnForSyntaxError() {
        DmSqlParser.ParseResult result = parser.parserStatements("SELECT ID FROM T;\nSELECT FROM T;");
        assertFalse(result.errors().isEmpty());
        assertEquals(2, result.errors().get(0).line());
    }

    @Test
    void unwrapsExplain() {
        DmStatement statement = parser.parseExecutableSql("EXPLAIN SELECT * FROM SYSOBJECTS");
        assertEquals(SqlType.EXPLAIN, statement.type());
        assertEquals("SELECT * FROM SYSOBJECTS", statement.executableSql());
        assertTrue(parser.startsWithExplain("/* plan */ explain SELECT * FROM SYSOBJECTS"));
        assertFalse(parser.startsWithExplain("SELECT 'EXPLAIN' FROM DUAL"));
    }
}
