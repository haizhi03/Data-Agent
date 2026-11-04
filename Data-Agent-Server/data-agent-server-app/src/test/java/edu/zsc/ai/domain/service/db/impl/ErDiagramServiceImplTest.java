package edu.zsc.ai.domain.service.db.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErDiagramServiceImplTest {

    @Test
    void defaultBatchKeepsTheFirstEightyAndReportsTheFullCount() {
        List<String> names = names(85);

        ErDiagramServiceImpl.SelectedTables selected = ErDiagramServiceImpl.selectTables(names, false, null);

        assertEquals(80, selected.names().size());
        assertEquals("t0", selected.names().get(0));
        assertEquals("t79", selected.names().get(79));
        assertEquals(85, selected.tableCount());
        assertTrue(ErDiagramServiceImpl.isTruncated(selected.names().size(), selected.tableCount()));
    }

    @Test
    void defaultBatchKeepsAShortSchemaIntact() {
        ErDiagramServiceImpl.SelectedTables selected = ErDiagramServiceImpl.selectTables(names(12), false, null);

        assertEquals(12, selected.names().size());
        assertEquals(12, selected.tableCount());
        assertFalse(ErDiagramServiceImpl.isTruncated(12, selected.tableCount()));
    }

    @Test
    void allTablesReplacesTheCapWithoutListingNames() {
        List<String> names = names(85);

        ErDiagramServiceImpl.SelectedTables selected = ErDiagramServiceImpl.selectTables(names, true, List.of("t3"));

        assertEquals(names, selected.names());
        assertEquals(85, selected.tableCount());
        assertFalse(ErDiagramServiceImpl.isTruncated(85, selected.tableCount()));
    }

    @Test
    void requestedNamesFollowMetadataSpellingAndOrder() {
        List<String> names = List.of("Orders", "Customers", "Payments");

        ErDiagramServiceImpl.SelectedTables selected = ErDiagramServiceImpl.selectTables(
                names, false, List.of("payments", "ORDERS", "missing", "orders", "  "));

        assertEquals(List.of("Orders", "Payments"), selected.names());
        assertEquals(3, selected.tableCount());
        assertTrue(ErDiagramServiceImpl.isTruncated(2, selected.tableCount()));
    }

    @Test
    void aLongNameListIsNotCutBackToEighty() {
        List<String> names = names(85);
        List<String> requested = new ArrayList<>();
        for (int i = 84; i >= 0; i--) {
            requested.add("T" + i);
        }

        ErDiagramServiceImpl.SelectedTables selected = ErDiagramServiceImpl.selectTables(names, false, requested);

        assertEquals(names, selected.names());
        assertEquals(85, selected.tableCount());
        assertFalse(ErDiagramServiceImpl.isTruncated(selected.names().size(), selected.tableCount()));
    }

    @Test
    void emptyRequestDrawsNothingInsteadOfTheDefaultSlice() {
        ErDiagramServiceImpl.SelectedTables selected = ErDiagramServiceImpl.selectTables(names(90), false, List.of());

        assertTrue(selected.names().isEmpty());
        assertEquals(90, selected.tableCount());
    }

    @Test
    void blankMetadataNamesAreNotPartOfTheSchemaCount() {
        List<String> names = new ArrayList<>();
        names.add("Orders");
        names.add(" ");
        names.add("Payments");

        ErDiagramServiceImpl.SelectedTables selected = ErDiagramServiceImpl.selectTables(names, false, null);

        assertEquals(List.of("Orders", " ", "Payments"), selected.names());
        assertEquals(2, selected.tableCount());
        assertFalse(ErDiagramServiceImpl.isTruncated(2, selected.tableCount()));
    }

    @Test
    void nullMetadataIsAnEmptySchema() {
        ErDiagramServiceImpl.SelectedTables selected = ErDiagramServiceImpl.selectTables(null, false, null);

        assertTrue(selected.names().isEmpty());
        assertEquals(0, selected.tableCount());
        assertFalse(ErDiagramServiceImpl.isTruncated(0, 0));
    }

    private static List<String> names(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("t" + i);
        }
        return names;
    }
}
