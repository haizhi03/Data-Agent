package edu.zsc.ai.plugin.dm.structure;

import edu.zsc.ai.plugin.model.structure.StructureDefaultKind;
import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.TableStructure;
import edu.zsc.ai.plugin.model.structure.TableStructure.Check;
import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import edu.zsc.ai.plugin.model.structure.TableStructure.ForeignKey;
import edu.zsc.ai.plugin.model.structure.TableStructure.Index;
import edu.zsc.ai.plugin.model.structure.TableStructure.PrimaryKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmTableStructureDialectTest {

    @Test
    void unchangedDraftProducesNoSql() {
        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", sample(), sample());

        assertTrue(preview.statements().isEmpty());
        assertTrue(preview.changes().isEmpty());
        assertTrue(preview.warnings().isEmpty());
    }

    @Test
    void addsColumnWithoutPositionClause() {
        TableStructure draft = copy(sample(), List.of(column("ID"), column("NAME"), added("NOTE")));

        String sql = DmTableStructureDialect.preview(null, "HR", sample(), draft).statements().get(0);

        assertEquals("ALTER TABLE \"HR\".\"DEMO\" ADD \"NOTE\" VARCHAR(255) NULL", sql);
    }

    @Test
    void renamesColumn() {
        Column renamed = column("TITLE", "NAME", "VARCHAR(100)", true, "");
        TableStructure draft = copy(sample(), List.of(column("ID"), renamed));

        String sql = DmTableStructureDialect.preview(null, "HR", sample(), draft).statements().get(0);

        assertEquals("ALTER TABLE \"HR\".\"DEMO\" RENAME COLUMN \"NAME\" TO \"TITLE\"", sql);
    }

    @Test
    void commentOnlyChangeUsesCommentOn() {
        Column commented = column("NAME", "NAME", "VARCHAR(100)", true, "客户名");
        TableStructure draft = copy(sample(), List.of(column("ID"), commented));

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", sample(), draft);

        assertEquals(false, preview.changes().get(0).destructive());
        assertEquals("COMMENT ON COLUMN \"HR\".\"DEMO\".\"NAME\" IS '客户名'", preview.statements().get(0));
    }

    @Test
    void typeChangeModifiesColumnInPlace() {
        Column wider = column("NAME", "NAME", "VARCHAR(200)", true, "");
        TableStructure draft = copy(sample(), List.of(column("ID"), wider));

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", sample(), draft);

        assertTrue(preview.changes().get(0).destructive());
        assertEquals("ALTER TABLE \"HR\".\"DEMO\" MODIFY \"NAME\" VARCHAR(200) NULL", preview.statements().get(0));
    }

    @Test
    void columnReorderIsAWarningWithoutSql() {
        TableStructure baseline = copy(sample(), List.of(column("ID"), column("NAME"), column("NOTE")));
        TableStructure draft = copy(baseline, List.of(column("ID"), column("NOTE"), column("NAME")));

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(preview.warnings().stream().anyMatch(warning -> warning.contains("cannot reorder")));
    }

    @Test
    void dropsForeignKeyBeforeDroppedColumn() {
        TableStructure baseline = withForeignKey(copy(sample(), List.of(column("ID"), column("NAME"), column("PARENT_ID"))));
        TableStructure draft = copy(sample(), List.of(column("ID"), column("NAME")));

        List<String> statements = DmTableStructureDialect.preview(null, "HR", baseline, draft).statements();

        assertEquals("ALTER TABLE \"HR\".\"DEMO\" DROP CONSTRAINT \"FK_DEMO\"", statements.get(0));
        assertTrue(statements.get(1).contains("DROP COLUMN \"PARENT_ID\""));
    }

    @Test
    void replacesPrimaryKeyByConstraintName() {
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                baseline.columns(), new PrimaryKey("PK_DEMO", List.of("ID", "NAME")), baseline.indexes(),
                baseline.foreignKeys(), baseline.checks());

        List<String> statements = DmTableStructureDialect.preview(null, "HR", baseline, draft).statements();

        assertEquals("ALTER TABLE \"HR\".\"DEMO\" DROP CONSTRAINT \"PK_DEMO\"", statements.get(0));
        assertEquals("ALTER TABLE \"HR\".\"DEMO\" ADD CONSTRAINT \"PK_DEMO\" PRIMARY KEY (\"ID\", \"NAME\")", statements.get(1));
    }

    @Test
    void createsUniqueIndexAsItsOwnStatement() {
        Index index = new Index("UK_NAME", null, true, "NORMAL", List.of("NAME"), List.of());
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                baseline.columns(), baseline.primaryKey(), List.of(index), baseline.foreignKeys(), baseline.checks());

        String sql = DmTableStructureDialect.preview(null, "HR", baseline, draft).statements().get(0);

        assertEquals("CREATE UNIQUE INDEX \"HR\".\"UK_NAME\" ON \"HR\".\"DEMO\" (\"NAME\")", sql);
    }

    @Test
    void addsCheckConstraint() {
        Check check = new Check("CHK_NAME", null, "LENGTH(\"NAME\") > 0");
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                baseline.columns(), baseline.primaryKey(), baseline.indexes(), baseline.foreignKeys(), List.of(check));

        String sql = DmTableStructureDialect.preview(null, "HR", baseline, draft).statements().get(0);

        assertEquals("ALTER TABLE \"HR\".\"DEMO\" ADD CONSTRAINT \"CHK_NAME\" CHECK (LENGTH(\"NAME\") > 0)", sql);
    }

    @Test
    void updatesTableComment() {
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), "活动", null, null, null,
                baseline.columns(), baseline.primaryKey(), baseline.indexes(), baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", baseline, draft);

        assertEquals(false, preview.changes().get(0).destructive());
        assertEquals("COMMENT ON TABLE \"HR\".\"DEMO\" IS '活动'", preview.statements().get(0));
    }

    @Test
    void ignoresEngineCharsetAndCollation() {
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), "InnoDB", "utf8mb4",
                "utf8mb4_unicode_ci", baseline.columns(), baseline.primaryKey(), baseline.indexes(),
                baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(preview.warnings().stream().anyMatch(warning -> warning.contains("character set")));
    }

    @Test
    void addsColumnBeforeIndexOnThatColumn() {
        Column code = column("CODE", null, "VARCHAR(100)", true, "");
        Index index = new Index("IDX_CODE", null, false, "NORMAL", List.of("CODE"), List.of());
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                List.of(column("ID"), column("NAME"), code), baseline.primaryKey(),
                List.of(index), baseline.foreignKeys(), baseline.checks());

        List<String> statements = DmTableStructureDialect.preview(null, "HR", baseline, draft).statements();

        int addAt = indexOf(statements, "ADD \"CODE\"");
        int indexAt = indexOf(statements, "CREATE INDEX \"HR\".\"IDX_CODE\"");
        assertTrue(addAt >= 0 && indexAt > addAt);
    }

    @Test
    void addsUniqueIndexOnNewColumnAfterAdd() {
        Column code = column("CODE", null, "VARCHAR(100)", true, "");
        Index index = new Index("UK_CODE", null, true, "NORMAL", List.of("CODE"), List.of());
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                List.of(column("ID"), column("NAME"), code), baseline.primaryKey(),
                List.of(index), baseline.foreignKeys(), baseline.checks());

        List<String> statements = DmTableStructureDialect.preview(null, "HR", baseline, draft).statements();

        int addAt = indexOf(statements, "ADD \"CODE\"");
        int indexAt = indexOf(statements, "CREATE UNIQUE INDEX \"HR\".\"UK_CODE\"");
        assertTrue(addAt >= 0 && indexAt > addAt);
    }

    @Test
    void dropsIndexBeforeDroppedColumn() {
        Index index = new Index("IDX_NOTE", "IDX_NOTE", false, "NORMAL", List.of("NOTE"), List.of());
        TableStructure baseline = new TableStructure(sample().name(), sample().comment(), null, null, null,
                List.of(column("ID"), column("NAME"), column("NOTE")), sample().primaryKey(),
                List.of(index), sample().foreignKeys(), sample().checks());
        TableStructure draft = copy(sample(), List.of(column("ID"), column("NAME")));

        List<String> statements = DmTableStructureDialect.preview(null, "HR", baseline, draft).statements();

        int dropIndex = indexOf(statements, "DROP INDEX \"HR\".\"IDX_NOTE\"");
        int dropColumn = indexOf(statements, "DROP COLUMN \"NOTE\"");
        assertTrue(dropIndex >= 0 && dropColumn > dropIndex);
    }

    @Test
    void clearingExistingIndexColumnsBlocksDrop() {
        Index index = new Index("UK_CODE", "UK_CODE", true, "NORMAL", List.of("NAME"), List.of());
        Index cleared = new Index("UK_CODE", "UK_CODE", true, "NORMAL", List.of(), List.of());
        TableStructure baseline = withIndexes(sample(), List.of(index));
        Column commented = column("NAME", "NAME", "VARCHAR(100)", true, "客户名");
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                List.of(column("ID"), commented), baseline.primaryKey(), List.of(cleared),
                baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(preview.changes().isEmpty());
        assertTrue(preview.warnings().stream().anyMatch(warning -> warning.contains("Index has no columns: UK_CODE")));
    }

    @Test
    void loadedIndexWithoutColumnsDoesNotBlockOtherChanges() {
        Index index = new Index("INDEX33555775", "INDEX33555775", false, "NORMAL", List.of(), List.of());
        Column commented = column("NAME", "NAME", "VARCHAR(100)", true, "客户名");
        TableStructure baseline = withIndexes(sample(), List.of(index));
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                List.of(column("ID"), commented), baseline.primaryKey(), List.of(index),
                baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", baseline, draft);

        assertEquals("COMMENT ON COLUMN \"HR\".\"DEMO\".\"NAME\" IS '客户名'", preview.statements().get(0));
        assertTrue(preview.warnings().isEmpty());
    }

    @Test
    void blankNewIndexDoesNotBlockOtherChanges() {
        Index blank = new Index("", null, false, "NORMAL", List.of(), List.of());
        Column commented = column("NAME", "NAME", "VARCHAR(100)", true, "客户名");
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                List.of(column("ID"), commented), baseline.primaryKey(), List.of(blank),
                baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", baseline, draft);

        assertEquals("COMMENT ON COLUMN \"HR\".\"DEMO\".\"NAME\" IS '客户名'", preview.statements().get(0));
        assertTrue(preview.warnings().isEmpty());
    }

    @Test
    void prefixLengthDoesNotCreateIndex() {
        Index index = new Index("IDX_NAME", null, false, "NORMAL", List.of("NAME"), List.of(10));
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                baseline.columns(), baseline.primaryKey(), List.of(index), baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", baseline, draft);

        assertTrue(preview.statements().stream().noneMatch(sql -> sql.contains("CREATE INDEX")));
        assertTrue(preview.warnings().stream().anyMatch(warning -> warning.contains("prefix")));
    }

    @Test
    void indexOnMissingColumnIsNotCreated() {
        Index index = new Index("IDX_MISSING", null, false, "NORMAL", List.of("MISSING"), List.of());
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                baseline.columns(), baseline.primaryKey(), List.of(index), baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = DmTableStructureDialect.preview(null, "HR", baseline, draft);

        assertTrue(preview.statements().stream().noneMatch(sql -> sql.contains("CREATE INDEX")));
        assertTrue(preview.warnings().stream().anyMatch(warning -> warning.contains("missing column")));
    }

    @Test
    void primaryKeyOnNewColumnComesAfterAdd() {
        Column code = column("CODE", null, "VARCHAR(100)", true, "");
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), null, null, null,
                List.of(column("ID"), column("NAME"), code), new PrimaryKey("PK_DEMO", List.of("ID", "CODE")),
                baseline.indexes(), baseline.foreignKeys(), baseline.checks());

        List<String> statements = DmTableStructureDialect.preview(null, "HR", baseline, draft).statements();

        int addAt = indexOf(statements, "ADD \"CODE\"");
        int keyAt = indexOf(statements, "PRIMARY KEY");
        assertTrue(addAt >= 0 && keyAt > addAt);
    }

    private static TableStructure sample() {
        return new TableStructure(
                "DEMO",
                "",
                null,
                null,
                null,
                List.of(column("ID"), column("NAME")),
                new PrimaryKey("PK_DEMO", List.of("ID")),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static TableStructure withForeignKey(TableStructure structure) {
        ForeignKey foreignKey = new ForeignKey(
                "FK_DEMO", "FK_DEMO", List.of("PARENT_ID"), "HR", "PARENT", List.of("ID"), null, null);
        return new TableStructure(structure.name(), structure.comment(), null, null, null, structure.columns(),
                structure.primaryKey(), structure.indexes(), List.of(foreignKey), structure.checks());
    }

    private static TableStructure withIndexes(TableStructure structure, List<Index> indexes) {
        return new TableStructure(structure.name(), structure.comment(), null, null, null, structure.columns(),
                structure.primaryKey(), indexes, structure.foreignKeys(), structure.checks());
    }

    private static TableStructure copy(TableStructure structure, List<Column> columns) {
        return new TableStructure(structure.name(), structure.comment(), null, null, null, columns,
                structure.primaryKey(), structure.indexes(), structure.foreignKeys(), structure.checks());
    }

    private static Column column(String name) {
        if ("ID".equals(name)) {
            return column(name, name, "BIGINT", false, "");
        }
        if ("NOTE".equals(name) || "PARENT_ID".equals(name)) {
            return column(name, name, "VARCHAR(255)", true, "");
        }
        return column(name, name, "VARCHAR(100)", true, "");
    }

    private static Column added(String name) {
        return column(name, null, "VARCHAR(255)", true, "");
    }

    private static Column column(String name, String originalName, String columnType, boolean nullable, String comment) {
        return new Column(
                name,
                originalName,
                columnType.startsWith("BIGINT") ? "BIGINT" : "VARCHAR",
                columnType,
                null,
                null,
                nullable,
                "ID".equals(name) && "BIGINT".equals(columnType),
                false,
                StructureDefaultKind.NONE,
                null,
                null,
                comment,
                null,
                null,
                null,
                false
        );
    }

    private static int indexOf(List<String> statements, String part) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(part)) {
                return i;
            }
        }
        return -1;
    }
}
