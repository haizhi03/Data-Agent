package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestFixture;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestRun;
import edu.zsc.ai.plugin.dm.structure.DmTableStructureDialect;
import edu.zsc.ai.plugin.dm.structure.DmTableStructureLoader;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.TableStructure;
import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renaming a column or changing only its comment must not rewrite defaults, indexes,
 * foreign keys, or checks. Run with {@code -Ddm.live=true} and {@code -Ddm.password}.
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmStructureAttributeRoundTripLiveTest {

    private final DmTableStructureLoader loader = new DmTableStructureLoader(new DmMetadataSupport(), new DmObjectQuerySupport());

    @Test
    void commentAndRenameKeepEveryOtherAttribute() throws Exception {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        try (Connection connection = new Dm8Plugin().connect(config)) {
            connection.setAutoCommit(true);
            String schema = currentUser(connection);
            try (DmLiveTestRun run = DmLiveTestRun.start(connection, schema, "M1E_")) {
                String parent = run.name("PARENT");
                String child = run.name("CHILD");
                execute(connection, "CREATE TABLE \"" + schema + "\".\"" + parent + "\" (\"ID\" INT NOT NULL, PRIMARY KEY (\"ID\"))");
                run.track("TABLE", parent);
                execute(connection, "CREATE TABLE \"" + schema + "\".\"" + child + "\" ("
                        + "\"ID\" INT NOT NULL, "
                        + "\"CODE\" VARCHAR(20) NOT NULL, "
                        + "\"DIGIT_TXT\" VARCHAR(10) DEFAULT '0001', "
                        + "PRIMARY KEY (\"ID\"), "
                        + "CONSTRAINT \"" + child + "_FK\" FOREIGN KEY (\"ID\") REFERENCES \"" + schema + "\".\"" + parent + "\" (\"ID\")"
                        + ")");
                run.track("TABLE", child);
                execute(connection, "CREATE INDEX \"" + child + "_IDX\" ON \"" + schema + "\".\"" + child + "\" (\"CODE\")");
                execute(connection, "ALTER TABLE \"" + schema + "\".\"" + child + "\" ADD CONSTRAINT \"" + child + "_CHK\" CHECK (LENGTH(\"CODE\") > 0)");

                TableStructure loaded = loader.load(connection, null, schema, child);
                Column digit = column(loaded, "DIGIT_TXT");
                TableStructure commented = replaceColumn(loaded, withComment(digit, "编号"));
                StructurePreview commentPlan = DmTableStructureDialect.preview(null, schema, loaded, commented);
                assertTrue(commentPlan.statements().stream().noneMatch(sql -> sql.contains("DROP")));
                assertTrue(commentPlan.statements().stream().anyMatch(sql -> sql.contains("COMMENT ON COLUMN")));
                apply(connection, commentPlan);

                TableStructure afterComment = loader.load(connection, null, schema, child);
                assertSameAttributes(loaded, afterComment, "DIGIT_TXT", "DIGIT_TXT");
                assertEquals("编号", column(afterComment, "DIGIT_TXT").comment());

                TableStructure renamed = replaceColumn(afterComment, withName(column(afterComment, "DIGIT_TXT"), "DIGIT_CODE"));
                StructurePreview renamePlan = DmTableStructureDialect.preview(null, schema, afterComment, renamed);
                assertTrue(renamePlan.statements().stream().noneMatch(sql -> sql.startsWith("DROP") || sql.contains(" DROP ")));
                apply(connection, renamePlan);

                TableStructure afterRename = loader.load(connection, null, schema, child);
                assertSameAttributes(afterComment, afterRename, "DIGIT_TXT", "DIGIT_CODE");
                assertEquals("0001", column(afterRename, "DIGIT_CODE").defaultValue());
            }
        }
    }

    private static void assertSameAttributes(TableStructure before, TableStructure after, String fromName, String toName) {
        Column left = column(before, fromName);
        Column right = column(after, toName);
        assertEquals(left.columnType(), right.columnType());
        assertEquals(left.length(), right.length());
        assertEquals(left.nullable(), right.nullable());
        assertEquals(left.defaultKind(), right.defaultKind());
        assertEquals(left.defaultValue(), right.defaultValue());
        assertEquals(indexColumns(before), indexColumns(after));
        assertEquals(before.foreignKeys().size(), after.foreignKeys().size());
        assertFalse(after.foreignKeys().isEmpty());
        assertEquals(before.checks().size(), after.checks().size());
        assertFalse(after.checks().isEmpty());
    }

    private static List<String> indexColumns(TableStructure structure) {
        List<String> columns = new ArrayList<>();
        structure.indexes().forEach(index -> columns.addAll(index.columns()));
        columns.sort(String::compareTo);
        return columns;
    }

    private static TableStructure replaceColumn(TableStructure structure, Column replacement) {
        List<Column> columns = new ArrayList<>();
        for (Column column : structure.columns()) {
            columns.add(column.name().equals(replacement.originalName()) || column.name().equals(replacement.name())
                    ? replacement : column);
        }
        return new TableStructure(structure.name(), structure.comment(), structure.engine(), structure.charset(),
                structure.collation(), columns, structure.primaryKey(), structure.indexes(),
                structure.foreignKeys(), structure.checks());
    }

    private static Column withComment(Column column, String comment) {
        return new Column(column.name(), column.originalName(), column.typeName(), column.columnType(),
                column.length(), column.scale(), column.nullable(), column.autoIncrement(), column.unsigned(),
                column.defaultKind(), column.defaultValue(), column.onUpdate(), comment,
                column.charset(), column.collation(), column.generationExpression(), column.generationStored());
    }

    private static Column withName(Column column, String name) {
        return new Column(name, column.name(), column.typeName(), column.columnType(),
                column.length(), column.scale(), column.nullable(), column.autoIncrement(), column.unsigned(),
                column.defaultKind(), column.defaultValue(), column.onUpdate(), column.comment(),
                column.charset(), column.collation(), column.generationExpression(), column.generationStored());
    }

    private static Column column(TableStructure structure, String name) {
        return structure.columns().stream().filter(column -> name.equals(column.name())).findFirst().orElseThrow();
    }

    private static void apply(Connection connection, StructurePreview preview) throws Exception {
        for (String sql : preview.statements()) {
            execute(connection, sql);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String currentUser(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT USER FROM DUAL")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
