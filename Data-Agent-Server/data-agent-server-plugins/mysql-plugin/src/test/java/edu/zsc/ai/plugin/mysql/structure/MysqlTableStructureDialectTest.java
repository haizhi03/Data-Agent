package edu.zsc.ai.plugin.mysql.structure;

import edu.zsc.ai.plugin.model.structure.StructureDefaultKind;
import edu.zsc.ai.plugin.model.structure.StructureDiagnostic;
import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.TableStructure;
import edu.zsc.ai.plugin.model.structure.TableStructure.Check;
import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import edu.zsc.ai.plugin.model.structure.TableStructure.ForeignKey;
import edu.zsc.ai.plugin.model.structure.TableStructure.Index;
import edu.zsc.ai.plugin.model.structure.TableStructure.PrimaryKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlTableStructureDialectTest {

    @Test
    void unchangedDraftProducesNoSql() {
        TableStructure structure = sample();

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, structure, structure);

        assertTrue(preview.statements().isEmpty());
        assertTrue(preview.changes().isEmpty());
        assertTrue(preview.warnings().isEmpty());
    }

    @Test
    void addsColumnAfterTheLastColumn() {
        TableStructure baseline = sample();
        TableStructure draft = copy(baseline, List.of(column("id"), column("name"), added("note")));

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertEquals(1, preview.statements().size());
        assertTrue(preview.statements().get(0).contains("ADD COLUMN `note` varchar(255) NULL AFTER `name`"));
        assertEquals("ADD", preview.changes().get(0).action());
        assertTrue(preview.changes().stream().noneMatch(StructurePreview.Change::destructive));
    }

    @Test
    void renamesColumnWithChange() {
        Column renamed = column("title", "name", "varchar(100)", 100, true, "");
        TableStructure baseline = sample();
        TableStructure draft = copy(baseline, List.of(column("id"), renamed));

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("CHANGE COLUMN `name` `title` varchar(100) NULL"));
    }

    @Test
    void commentOnlyChangeIsNotDestructive() {
        Column commented = column("name", "name", "varchar(100)", 100, true, "客户名");
        TableStructure baseline = sample();
        TableStructure draft = copy(baseline, List.of(column("id"), commented));

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertEquals(false, preview.changes().get(0).destructive());
        assertTrue(preview.statements().get(0).contains("COMMENT '客户名'"));
    }

    @Test
    void typeChangeIsDestructive() {
        Column wider = column("name", "name", "varchar(200)", 200, true, "");
        TableStructure baseline = sample();
        TableStructure draft = copy(baseline, List.of(column("id"), wider));

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertEquals("MODIFY", preview.changes().get(0).action());
        assertTrue(preview.changes().get(0).destructive());
        assertTrue(preview.statements().get(0).contains("MODIFY COLUMN `name` varchar(200) NULL"));
    }

    @Test
    void dropsForeignKeyBeforeDroppedColumn() {
        TableStructure baseline = copy(sample(), List.of(column("id"), column("name"), column("customer_id")));
        baseline = withForeignKey(baseline);
        TableStructure draft = copy(sample(), List.of(column("id"), column("name")));

        List<String> statements = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements();

        assertTrue(statements.get(0).contains("DROP FOREIGN KEY `fk_activity_customer`"));
        assertTrue(statements.get(1).contains("DROP COLUMN `customer_id`"));
    }

    @Test
    void replacesPrimaryKeyInOneAlter() {
        TableStructure baseline = sample();
        TableStructure draft = copy(baseline, baseline.columns());
        draft = new TableStructure(draft.name(), draft.comment(), draft.engine(), draft.charset(), draft.collation(),
                draft.columns(), new PrimaryKey("PRIMARY", List.of("id", "name")), draft.indexes(),
                draft.foreignKeys(), draft.checks());

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("DROP PRIMARY KEY"));
        assertTrue(sql.contains("ADD PRIMARY KEY (`id`, `name`)"));
        assertEquals(1, MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().size());
    }

    @Test
    void movesColumn() {
        TableStructure baseline = copy(sample(), List.of(column("id"), column("name"), column("note")));
        TableStructure draft = copy(baseline, List.of(column("id"), column("note"), column("name")));

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("MODIFY COLUMN `note` varchar(255) NULL AFTER `id`"));
        assertTrue(!sql.contains("MODIFY COLUMN `name`"));
    }

    @Test
    void blankExistingNameBlocksSql() {
        Column blank = column("", "name", "varchar(100)", 100, true, "");
        TableStructure baseline = sample();
        TableStructure draft = copy(baseline, List.of(column("id"), blank));

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(hasCode(preview, StructureDiagnostic.COLUMN_NAME_EMPTY));
    }

    @Test
    void updatesTableComment() {
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), "活动记录", baseline.engine(), baseline.charset(),
                baseline.collation(), baseline.columns(), baseline.primaryKey(), baseline.indexes(),
                baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertEquals(false, preview.changes().get(0).destructive());
        assertTrue(preview.statements().get(0).contains("COMMENT='活动记录'"));
    }

    @Test
    void addsUniqueIndex() {
        TableStructure baseline = sample();
        Index index = new Index("uk_name", null, true, "BTREE", List.of("name"), List.of());
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), baseline.engine(),
                baseline.charset(), baseline.collation(), baseline.columns(), baseline.primaryKey(),
                List.of(index), baseline.foreignKeys(), baseline.checks());

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("ADD UNIQUE INDEX `uk_name` (`name`)"));
    }

    @Test
    void addsCheckConstraint() {
        TableStructure baseline = sample();
        Check check = new Check("chk_name", null, "char_length(`name`) > 0");
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), baseline.engine(),
                baseline.charset(), baseline.collation(), baseline.columns(), baseline.primaryKey(),
                baseline.indexes(), baseline.foreignKeys(), List.of(check));

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("ADD CONSTRAINT `chk_name` CHECK (char_length(`name`) > 0)"));
    }

    @Test
    void commentOnlyKeepsStringDefaultQuoted() {
        TableStructure baseline = copy(sample(), List.of(column("id"), stringColumn("007", "")));
        TableStructure draft = copy(baseline, List.of(column("id"), stringColumn("007", "客户名")));

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("DEFAULT '007'"));
        assertTrue(sql.contains("COMMENT '客户名'"));
    }

    @Test
    void commentOnlyKeepsTrueStringDefaultQuoted() {
        TableStructure baseline = copy(sample(), List.of(column("id"), stringColumn("true", "")));
        TableStructure draft = copy(baseline, List.of(column("id"), stringColumn("true", "客户名")));

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("DEFAULT 'true'"));
    }

    @Test
    void numericDefaultStaysUnquoted() {
        Column baselineColumn = numericColumn("");
        Column draftColumn = numericColumn("数量");
        TableStructure baseline = copy(sample(), List.of(column("id"), baselineColumn));
        TableStructure draft = copy(baseline, List.of(column("id"), draftColumn));

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("DEFAULT 12"));
        assertTrue(!sql.contains("DEFAULT '12'"));
    }

    @Test
    void commentOnlyKeepsCollation() {
        TableStructure baseline = copy(sample(), List.of(column("id"), collated("")));
        TableStructure draft = copy(baseline, List.of(column("id"), collated("客户名")));

        String sql = MysqlTableStructureDialect.preview("crm", null, baseline, draft).statements().get(0);

        assertTrue(sql.contains("COLLATE utf8mb4_bin"));
        assertTrue(sql.contains("COMMENT '客户名'"));
    }

    @Test
    void commentOnlyKeepsStoredGeneratedColumn() {
        TableStructure baseline = copy(sample(), List.of(column("id"), generated("varchar(100)", "concat(`first`,`last`)", true, "")));
        TableStructure draft = copy(baseline, List.of(column("id"), generated("varchar(100)", "concat(`first`,`last`)", true, "全名")));

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertEquals(false, preview.changes().get(0).destructive());
        assertTrue(preview.statements().get(0).contains("GENERATED ALWAYS AS (concat(`first`,`last`)) STORED"));
        assertTrue(preview.statements().get(0).contains("COMMENT '全名'"));
    }

    @Test
    void generatedColumnTypeChangeIsBlocked() {
        TableStructure baseline = copy(sample(), List.of(column("id"), generated("varchar(100)", "concat(`first`,`last`)", true, "")));
        TableStructure draft = copy(baseline, List.of(column("id"), generated("varchar(200)", "concat(`first`,`last`)", true, "")));

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(hasCode(preview, StructureDiagnostic.GENERATED_COLUMN_LOCKED));
    }

    @Test
    void expressionIndexCannotBeEdited() {
        List<String> columns = new ArrayList<>();
        columns.add(null);
        Index index = new Index("idx_expr", "idx_expr", false, "BTREE", columns, List.of());
        TableStructure baseline = new TableStructure(sample().name(), sample().comment(), sample().engine(),
                sample().charset(), sample().collation(), sample().columns(), sample().primaryKey(),
                List.of(index), sample().foreignKeys(), sample().checks());
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), baseline.engine(),
                baseline.charset(), baseline.collation(), baseline.columns(), baseline.primaryKey(),
                List.of(), baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(hasCode(preview, StructureDiagnostic.EXPRESSION_INDEX_LOCKED));
    }

    @Test
    void clearingExistingIndexColumnsBlocksDrop() {
        Index index = new Index("uk_name", "uk_name", true, "BTREE", List.of("name"), List.of());
        Index cleared = new Index("uk_name", "uk_name", true, "BTREE", List.of(), List.of());
        TableStructure baseline = withIndexes(sample(), List.of(index));
        Column commented = column("name", "name", "varchar(100)", 100, true, "客户名");
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), baseline.engine(),
                baseline.charset(), baseline.collation(), List.of(column("id"), commented), baseline.primaryKey(),
                List.of(cleared), baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(preview.changes().isEmpty());
        assertTrue(hasCode(preview, StructureDiagnostic.INDEX_HAS_NO_COLUMNS));
    }

    @Test
    void invalidForeignKeyReplacementBlocksTheWholePlan() {
        ForeignKey foreignKey = new ForeignKey("fk_name", "fk_name", List.of("name"), "crm", "parent", List.of("id"), null, null);
        ForeignKey broken = new ForeignKey("fk_name", "fk_name", List.of(), "crm", "parent", List.of("id"), null, null);
        Column commented = column("name", "name", "varchar(100)", 100, true, "客户名");
        TableStructure baseline = new TableStructure(sample().name(), sample().comment(), sample().engine(),
                sample().charset(), sample().collation(), sample().columns(), sample().primaryKey(),
                List.of(), List.of(foreignKey), sample().checks());
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), baseline.engine(),
                baseline.charset(), baseline.collation(), List.of(column("id"), commented), baseline.primaryKey(),
                List.of(), List.of(broken), baseline.checks());

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(hasCode(preview, StructureDiagnostic.FOREIGN_KEY_INCOMPLETE));
    }

    @Test
    void invalidCheckReplacementBlocksTheWholePlan() {
        Check check = new Check("chk_name", "chk_name", "char_length(`name`) > 0");
        Check broken = new Check("chk_name", "chk_name", "char_length(`name`) > 0; drop table x");
        Column commented = column("name", "name", "varchar(100)", 100, true, "客户名");
        TableStructure baseline = new TableStructure(sample().name(), sample().comment(), sample().engine(),
                sample().charset(), sample().collation(), sample().columns(), sample().primaryKey(),
                List.of(), List.of(), List.of(check));
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), baseline.engine(),
                baseline.charset(), baseline.collation(), List.of(column("id"), commented), baseline.primaryKey(),
                List.of(), List.of(), List.of(broken));

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertTrue(preview.statements().isEmpty());
        assertTrue(hasCode(preview, StructureDiagnostic.CHECK_HAS_SEMICOLON));
    }

    @Test
    void removingIndexDropsIt() {
        Index index = new Index("uk_name", "uk_name", true, "BTREE", List.of("name"), List.of());
        TableStructure baseline = withIndexes(sample(), List.of(index));
        TableStructure draft = withIndexes(baseline, List.of());

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertTrue(preview.statements().get(0).contains("DROP INDEX `uk_name`"));
        assertEquals("DROP", preview.changes().get(0).action());
    }

    @Test
    void blankNewIndexDoesNotBlockOtherChanges() {
        Index blank = new Index("", null, false, "BTREE", List.of(), List.of());
        Column commented = column("name", "name", "varchar(100)", 100, true, "客户名");
        TableStructure baseline = sample();
        TableStructure draft = new TableStructure(baseline.name(), baseline.comment(), baseline.engine(),
                baseline.charset(), baseline.collation(), List.of(column("id"), commented), baseline.primaryKey(),
                List.of(blank), baseline.foreignKeys(), baseline.checks());

        StructurePreview preview = MysqlTableStructureDialect.preview("crm", null, baseline, draft);

        assertTrue(preview.statements().get(0).contains("COMMENT '客户名'"));
        assertTrue(preview.warnings().isEmpty());
    }

    @Test
    void keepsCollationWhenCharsetMatchesTable() {
        assertEquals("utf8mb4_bin", MysqlTableStructureLoader.retainedCollation("utf8mb4", "utf8mb4", "utf8mb4_bin"));
    }

    @Test
    void defaultGeneratedIsNotAGeneratedColumn() {
        assertEquals(null, MysqlTableStructureLoader.generationExpression("DEFAULT_GENERATED", "(uuid())"));
        assertEquals("(`a` + `b`)", MysqlTableStructureLoader.generationExpression("STORED GENERATED", "(`a` + `b`)"));
        assertTrue(MysqlTableStructureLoader.generationStored("STORED GENERATED"));
        assertTrue(!MysqlTableStructureLoader.generationStored("VIRTUAL GENERATED"));
    }

    private static TableStructure sample() {
        return new TableStructure(
                "crm_activity",
                "活动",
                "InnoDB",
                "utf8mb4",
                "utf8mb4_unicode_ci",
                List.of(column("id"), column("name")),
                new PrimaryKey("PRIMARY", List.of("id")),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static TableStructure withForeignKey(TableStructure structure) {
        ForeignKey foreignKey = new ForeignKey(
                "fk_activity_customer", "fk_activity_customer", List.of("customer_id"),
                "crm", "customer", List.of("id"), "RESTRICT", "CASCADE");
        return new TableStructure(structure.name(), structure.comment(), structure.engine(), structure.charset(),
                structure.collation(), structure.columns(), structure.primaryKey(), structure.indexes(),
                List.of(foreignKey), structure.checks());
    }

    private static TableStructure withIndexes(TableStructure structure, List<Index> indexes) {
        return new TableStructure(structure.name(), structure.comment(), structure.engine(), structure.charset(),
                structure.collation(), structure.columns(), structure.primaryKey(), indexes,
                structure.foreignKeys(), structure.checks());
    }

    private static TableStructure copy(TableStructure structure, List<Column> columns) {
        return new TableStructure(structure.name(), structure.comment(), structure.engine(), structure.charset(),
                structure.collation(), columns, structure.primaryKey(), structure.indexes(),
                structure.foreignKeys(), structure.checks());
    }

    private static Column column(String name) {
        if ("id".equals(name)) {
            return column(name, name, "bigint", null, false, "");
        }
        if ("note".equals(name) || "customer_id".equals(name)) {
            return column(name, name, "varchar(255)", 255, true, "");
        }
        return column(name, name, "varchar(100)", 100, true, "");
    }

    private static Column added(String name) {
        Column column = column(name, null, "varchar(255)", 255, true, "");
        return column;
    }

    private static Column column(String name, String originalName, String columnType, Integer length,
                                 boolean nullable, String comment) {
        boolean id = "id".equals(name) && "bigint".equals(columnType);
        return new Column(
                name,
                originalName,
                columnType.startsWith("bigint") ? "bigint" : "varchar",
                columnType,
                length,
                null,
                nullable,
                id,
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

    private static Column stringColumn(String defaultValue, String comment) {
        return new Column("name", "name", "varchar", "varchar(100)", 100, null, true, false, false,
                StructureDefaultKind.VALUE, defaultValue, null, comment, null, null, null, false);
    }

    private static Column numericColumn(String comment) {
        return new Column("qty", "qty", "int", "int", null, null, true, false, false,
                StructureDefaultKind.VALUE, "12", null, comment, null, null, null, false);
    }

    private static Column collated(String comment) {
        return new Column("name", "name", "varchar", "varchar(100)", 100, null, true, false, false,
                StructureDefaultKind.NONE, null, null, comment, null, "utf8mb4_bin", null, false);
    }

    private static Column generated(String columnType, String expression, boolean stored, String comment) {
        return new Column("name", "name", "varchar", columnType, null, null, true, false, false,
                StructureDefaultKind.NONE, null, null, comment, null, null, expression, stored);
    }

    private static boolean hasCode(StructurePreview preview, String code) {
        return preview.warnings().stream().anyMatch(warning -> code.equals(warning.code()));
    }
}
