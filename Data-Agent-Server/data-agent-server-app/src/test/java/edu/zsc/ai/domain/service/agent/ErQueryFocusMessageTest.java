package edu.zsc.ai.domain.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.zsc.ai.api.model.request.ErQueryFocus;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class ErQueryFocusMessageTest {

    private final ResourceBundleMessageSource messages = messages();

    @Test
    void leavesTheContextEmptyWhenNothingIsCircled() {
        assertEquals("", ErQueryFocusMessage.contextBlock(null, messages, Locale.CHINA));
        assertEquals("", ErQueryFocusMessage.contextBlock(new ErQueryFocus(), messages, Locale.CHINA));
    }

    @Test
    void keepsConnectionCatalogAndSchemaInTheModelContext() {
        ErQueryFocus focus = new ErQueryFocus();
        focus.setConnectionId(7L);
        focus.setConnectionName("shop-db");
        ErQueryFocus.Column id = column("id", "int", true);
        ErQueryFocus.Column addressId = column("address_id", "int", false);
        ErQueryFocus.Table orders = new ErQueryFocus.Table();
        orders.setName("orders");
        orders.setCatalog("shop");
        orders.setSchema("public");
        orders.setColumns(List.of(id, addressId));
        focus.setTables(List.of(orders));

        ErQueryFocus.Relation inside = relation("orders", List.of("id"), "order_item", List.of("order_id"));
        ErQueryFocus.Relation outside = relation("orders", List.of("address_id"), "address", List.of("id"));
        outside.setFromCatalog("shop");
        outside.setFromSchema("public");
        outside.setToCatalog("shop");
        outside.setToSchema("public");
        focus.setInsideRelations(List.of(inside));
        focus.setOutsideRelations(List.of(outside));

        String context = ErQueryFocusMessage.contextBlock(focus, messages, Locale.forLanguageTag("zh-CN"));

        assertTrue(context.contains("圈选不是白名单"));
        assertTrue(context.contains("连接：shop-db (id=7)"));
        assertTrue(context.contains("shop.public.orders: id PK int, address_id int"));
        assertTrue(context.contains("shop.public.orders.address_id -> shop.public.address.id"));
        assertTrue(!context.contains("按地区统计订单"));
        assertTrue(ErQueryFocusMessage.contextBlock(focus, messages, Locale.US).contains("Connection: shop-db (id=7)"));
    }

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(false);
        source.setFallbackToSystemLocale(false);
        return source;
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
