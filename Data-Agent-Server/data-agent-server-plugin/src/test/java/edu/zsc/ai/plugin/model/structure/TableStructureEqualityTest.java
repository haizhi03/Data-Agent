package edu.zsc.ai.plugin.model.structure;

import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import edu.zsc.ai.plugin.model.structure.TableStructure.PrimaryKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableStructureEqualityTest {

    @Test
    void sameSnapshotMatches() {
        assertTrue(TableStructureEquality.same(sample(200, false), sample(200, false)));
    }

    @Test
    void blankCommentMatchesNull() {
        TableStructure left = sample(200, false);
        Column code = column(200, false, null);
        TableStructure right = new TableStructure(left.name(), null, left.engine(), left.charset(), left.collation(),
                List.of(code), left.primaryKey(), List.of(), List.of(), List.of());

        assertTrue(TableStructureEquality.same(left, right));
    }

    @Test
    void lengthChangeDiffers() {
        assertFalse(TableStructureEquality.same(sample(200, false), sample(300, false)));
    }

    @Test
    void nullableChangeDiffers() {
        assertFalse(TableStructureEquality.same(sample(200, false), sample(200, true)));
    }

    private static TableStructure sample(int length, boolean nullable) {
        return new TableStructure(
                "PR20_DM_TEST",
                "",
                null,
                null,
                null,
                List.of(column(length, nullable, "")),
                new PrimaryKey("PK", List.of("CODE")),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static Column column(int length, boolean nullable, String comment) {
        return new Column(
                "CODE",
                "CODE",
                "VARCHAR",
                "VARCHAR(" + length + ")",
                length,
                null,
                nullable,
                false,
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
}
