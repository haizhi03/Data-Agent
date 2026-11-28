package edu.zsc.ai.domain.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.zsc.ai.api.model.request.ErQueryFocus;
import edu.zsc.ai.domain.model.dto.response.db.ErDiagramResponse;
import edu.zsc.ai.domain.service.db.ErDiagramService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ErQueryFocusResolverTest {

    @Test
    void verifiesTablesAndSplitsOneHopFromMetadata() {
        ErDiagramService diagrams = mock(ErDiagramService.class);
        when(diagrams.load(any(), eq(false), any())).thenReturn(ErDiagramResponse.builder()
                .tables(List.of(
                        ErDiagramResponse.ErTable.builder()
                                .name("orders")
                                .catalog("shop")
                                .schema("public")
                                .columns(List.of(ErDiagramResponse.ErColumn.builder()
                                        .name("id").typeName("int").primaryKey(true).build()))
                                .build(),
                        ErDiagramResponse.ErTable.builder()
                                .name("address")
                                .catalog("shop")
                                .schema("public")
                                .external(true)
                                .columns(List.of())
                                .build()))
                .relations(List.of(
                        ErDiagramResponse.ErRelation.builder()
                                .fromCatalog("shop").fromSchema("public").fromTable("orders")
                                .fromColumn("address_id").fromColumns(List.of("address_id"))
                                .toCatalog("shop").toSchema("public").toTable("address")
                                .toColumn("id").toColumns(List.of("id"))
                                .cardinality("ONE_TO_MANY")
                                .build(),
                        ErDiagramResponse.ErRelation.builder()
                                .fromCatalog("other").fromSchema("public").fromTable("ghost")
                                .fromColumn("id").fromColumns(List.of("id"))
                                .toCatalog("other").toSchema("public").toTable("nowhere")
                                .toColumn("id").toColumns(List.of("id"))
                                .cardinality("ONE_TO_MANY")
                                .build()))
                .build());

        ErQueryFocus request = new ErQueryFocus();
        request.setConnectionId(7L);
        request.setConnectionName("shop-db");
        request.setCatalogName("shop");
        request.setSchemaName("public");
        ErQueryFocus.Table orders = new ErQueryFocus.Table();
        orders.setName("Orders");
        ErQueryFocus.Table missing = new ErQueryFocus.Table();
        missing.setName("missing");
        request.setTables(List.of(orders, missing));

        ErQueryFocus resolved = new ErQueryFocusResolver(diagrams).resolve(request);

        assertEquals(1, resolved.getTables().size());
        assertEquals("orders", resolved.getTables().get(0).getName());
        assertEquals("id", resolved.getTables().get(0).getColumns().get(0).getName());
        assertTrue(resolved.getTables().get(0).getColumns().get(0).isPrimaryKey());
        assertTrue(resolved.getInsideRelations().isEmpty());
        assertEquals(1, resolved.getOutsideRelations().size());
        assertEquals("address", resolved.getOutsideRelations().get(0).getToTable());
        assertEquals("shop", resolved.getOutsideRelations().get(0).getToCatalog());
    }
}
