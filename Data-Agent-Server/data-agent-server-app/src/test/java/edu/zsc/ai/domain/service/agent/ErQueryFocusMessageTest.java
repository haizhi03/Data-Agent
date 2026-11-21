package edu.zsc.ai.domain.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.zsc.ai.api.model.request.ErQueryFocus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ErQueryFocusMessageTest {

    @Test
    void leavesTheMessageAloneWhenNothingIsCircled() {
        assertEquals("按地区统计", ErQueryFocusMessage.enrich("按地区统计", null, "zh"));
        ErQueryFocus empty = new ErQueryFocus();
        assertEquals("按地区统计", ErQueryFocusMessage.enrich("按地区统计", empty, "zh"));
    }

    @Test
    void putsCircledTablesAndBothKindsOfForeignKeysBeforeTheQuestion() {
        ErQueryFocus focus = new ErQueryFocus();
        ErQueryFocus.Column id = column("id", "int", true);
        ErQueryFocus.Column addressId = column("address_id", "int", false);
        ErQueryFocus.Table orders = new ErQueryFocus.Table();
        orders.setName("orders");
        orders.setCatalog("shop");
        orders.setColumns(List.of(id, addressId));
        focus.setTables(List.of(orders));

        ErQueryFocus.Relation inside = relation("orders", List.of("id"), "order_item", List.of("order_id"));
        ErQueryFocus.Relation outside = relation("orders", List.of("address_id"), "address", List.of("id"));
        focus.setInsideRelations(List.of(inside));
        focus.setOutsideRelations(List.of(outside));

        String enriched = ErQueryFocusMessage.enrich("按地区统计订单", focus, "zh-CN");

        assertTrue(enriched.contains("圈选不是白名单"));
        assertTrue(enriched.contains("orders (shop): id PK int, address_id int"));
        assertTrue(enriched.contains("orders.address_id -> address.id"));
        assertTrue(enriched.endsWith("用户问题：\n按地区统计订单"));
    }

    private static ErQueryFocus.Column column(String name, String type, boolean primaryKey) {
        ErQueryFocus.Column column = new ErQueryFocus.Column();
        column.setName(name);
        column.setTypeName(type);
        column.setPrimaryKey(primaryKey);
        return column;
    }

    private static ErQueryFocus.Relation relation(String from, List<String> fromColumns, String to, List<String> toColumns) {
        ErQueryFocus.Relation relation = new ErQueryFocus.Relation();
        relation.setFromTable(from);
        relation.setFromColumns(fromColumns);
        relation.setToTable(to);
        relation.setToColumns(toColumns);
        relation.setCardinality("ONE_TO_MANY");
        return relation;
    }
}
