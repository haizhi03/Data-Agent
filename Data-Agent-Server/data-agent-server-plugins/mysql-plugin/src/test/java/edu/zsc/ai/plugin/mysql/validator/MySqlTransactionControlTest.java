package edu.zsc.ai.plugin.mysql.validator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms MySQL coverage of the SqlValidator.isTransactionControl SPI (M1-07):
 * START TRANSACTION / BEGIN / COMMIT / ROLLBACK are intercepted via classifySql.
 *
 * <p>Known limitation: classification is first-token based, so
 * {@code ROLLBACK TO SAVEPOINT x} is also intercepted (whole-batch submission is
 * rejected either way) and statements hiding behind leading hints are not parsed.
 */
class MySqlTransactionControlTest {

    private final MySqlSqlValidator validator = new MySqlSqlValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "COMMIT",
            "commit;",
            "ROLLBACK",
            "rollback",
            "BEGIN",
            "START TRANSACTION",
            "start transaction",
            "-- note\nCOMMIT"
    })
    void detectsTransactionControl(String sql) {
        assertTrue(validator.isTransactionControl(sql), "expected transaction control: " + sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 'COMMIT' FROM t",
            "SELECT 1 -- commit",
            "UPDATE t SET c = 'rollback'",
            "",
            "   "
    })
    void ignoresNonTransactionControl(String sql) {
        assertFalse(validator.isTransactionControl(sql), "expected NOT transaction control: " + sql);
    }
}
