package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.metadata.SequenceMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmSequenceManagerTest {

    private final DmObjectQuerySupport support = mock(DmObjectQuerySupport.class);
    private final Connection connection = mock(Connection.class);
    private final DmSequenceManager manager = new DmSequenceManager(support);

    @Test
    void listsDefinitionWithoutAdvancingSequence() {
        when(support.resolveSchema(connection, "Mixed")).thenReturn("Mixed");
        when(support.query(connection, DmObjectSql.SQL_LIST_SEQUENCES, "Mixed")).thenReturn(List.of(Map.of(
                "SEQUENCE_NAME", "lowerSeq",
                "MIN_VALUE", BigDecimal.ONE,
                "MAX_VALUE", new BigDecimal("999"),
                "INCREMENT_BY", new BigDecimal("2"),
                "CYCLE_FLAG", "Y",
                "CACHE_SIZE", 10)));

        SequenceMetadata sequence = manager.getSequences(connection, null, "Mixed").get(0);
        assertEquals("lowerSeq", sequence.name());
        assertEquals(new BigDecimal("2"), sequence.incrementBy());
        assertEquals(10, sequence.cacheSize());
        assertFalse(DmObjectSql.SQL_LIST_SEQUENCES.contains("NEXTVAL"));
    }

    @Test
    void ddlAndDropPreserveExactIdentifier() {
        when(support.resolveSchema(connection, "Mixed")).thenReturn("Mixed");
        manager.getSequenceDdl(connection, null, "Mixed", "lowerSeq");
        verify(support).getObjectDdl(connection, "SEQUENCE", "Mixed", "lowerSeq");

        manager.deleteSequence(connection, null, "Mixed", "lowerSeq");
        verify(support).executeDdl(connection,
                "DROP SEQUENCE \"Mixed\".\"lowerSeq\"", "Failed to delete sequence");
    }
}
