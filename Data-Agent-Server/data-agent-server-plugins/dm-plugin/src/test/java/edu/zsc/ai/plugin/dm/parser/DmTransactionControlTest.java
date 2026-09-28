package edu.zsc.ai.plugin.dm.parser;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser-based transaction-control detection for DM (M1-07): COMMIT/ROLLBACK variants
 * must be caught; anonymous blocks, string literals and comments must not be misjudged.
 */
class DmTransactionControlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "COMMIT",
            "commit",
            "Commit;",
            "COMMIT WORK",
            "commit work;",
            "  -- leading comment\nCOMMIT",
            "/* block */ COMMIT",
            "ROLLBACK",
            "rollback",
            "ROLLBACK WORK",
            "SET TRANSACTION READ ONLY",
            "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"
    })
    void detectsTransactionControl(String sql) {
        assertTrue(DmSqlParser.INSTANCE.isTransactionControl(sql), "expected transaction control: " + sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 'COMMIT' FROM DUAL",
            "SELECT 1 FROM DUAL -- commit",
            "-- commit\nSELECT 1 FROM DUAL",
            "INSERT INTO T (C) VALUES ('rollback')",
            "ROLLBACK TO SAVEPOINT sp1",
            "rollback to savepoint sp1",
            "SAVEPOINT sp1",
            "UPDATE T SET C = 'COMMIT WORK'",
            "",
            "   "
    })
    void ignoresNonTransactionControl(String sql) {
        assertFalse(DmSqlParser.INSTANCE.isTransactionControl(sql), "expected NOT transaction control: " + sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BEGIN NULL; END;",
            "begin\n  update t set c = 1;\n  commit;\nend;",
            "DECLARE x INT; BEGIN x := 1; END;"
    })
    void anonymousBlockIsNotTransactionControl(String sql) {
        assertFalse(DmSqlParser.INSTANCE.isTransactionControl(sql),
                "anonymous block must not be intercepted: " + sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMMIT; SELECT 1 FROM DUAL",
            "ROLLBACK; ROLLBACK"
    })
    void multiStatementScriptsAreNotSingleTransactionControl(String sql) {
        assertFalse(DmSqlParser.INSTANCE.isTransactionControl(sql),
                "single-statement API must not treat scripts as one control statement: " + sql);
    }
}
