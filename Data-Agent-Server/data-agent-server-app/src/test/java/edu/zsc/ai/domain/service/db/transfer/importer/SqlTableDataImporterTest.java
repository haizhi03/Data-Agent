package edu.zsc.ai.domain.service.db.transfer.importer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlTableDataImporterTest {

    private final SqlTableDataImporter importer = new SqlTableDataImporter(null, null);

    @Test
    void dmSplitUsesOnlyDoubledQuoteEscapes() {
        String script = "INSERT INTO \"messages\" (\"value\") VALUES ('C:\\''');\n"
                + "INSERT INTO \"messages\" (\"value\") VALUES ('next');";

        List<String> statements = importer.splitSqlStatements(script, false);

        assertEquals(2, statements.size());
        assertEquals("INSERT INTO \"messages\" (\"value\") VALUES ('C:\\''')", statements.get(0));
    }

    @Test
    void mysqlSplitStillHonorsBackslashEscapes() {
        String script = "INSERT INTO `messages` (`value`) VALUES ('C:\\\\''');\n"
                + "INSERT INTO `messages` (`value`) VALUES ('next');";

        List<String> statements = importer.splitSqlStatements(script, true);

        assertEquals(2, statements.size());
        assertEquals("INSERT INTO `messages` (`value`) VALUES ('C:\\\\''')", statements.get(0));
    }
}
