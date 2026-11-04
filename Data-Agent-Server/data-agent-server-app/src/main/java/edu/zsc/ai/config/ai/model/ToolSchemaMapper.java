package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.node.*;
import dev.langchain4j.model.chat.request.json.*;

/** Preserve the locked SDK schema subset exactly; never widen a schema by dropping a construct. */
final class ToolSchemaMapper {
    private ToolSchemaMapper() {}
    static ObjectNode map(JsonSchemaElement schema) {
        ObjectNode node = ProtocolJson.object();
        if (schema == null) return node.put("type", "object").set("properties", ProtocolJson.object());
        if (schema.description() != null) node.put("description", schema.description());
        if (schema instanceof JsonObjectSchema object) {
            node.put("type", "object");
            ObjectNode properties = node.putObject("properties");
            object.properties().forEach((key, value) -> properties.set(key, map(value)));
            if (object.required() != null) { ArrayNode required = node.putArray("required"); object.required().forEach(required::add); }
            if (object.additionalProperties() != null) node.put("additionalProperties", object.additionalProperties());
            if (object.definitions() != null && !object.definitions().isEmpty()) {
                ObjectNode definitions = node.putObject("$defs"); object.definitions().forEach((key, value) -> definitions.set(key, map(value)));
            }
        } else if (schema instanceof JsonArraySchema array) node.put("type", "array").set("items", array.items() == null ? ProtocolJson.object() : map(array.items()));
        else if (schema instanceof JsonEnumSchema enumeration) { node.put("type", "string"); ArrayNode values = node.putArray("enum"); enumeration.enumValues().forEach(values::add); }
        else if (schema instanceof JsonAnyOfSchema any) { ArrayNode values = node.putArray("anyOf"); any.anyOf().forEach(value -> values.add(map(value))); }
        else if (schema instanceof JsonReferenceSchema ref) {
            if (ref.reference() == null) throw ModelIntegrationException.capability();
            node.put("$ref", "#/$defs/" + ref.reference().replace("~", "~0").replace("/", "~1"));
        }
        else if (schema instanceof JsonStringSchema) node.put("type", "string");
        else if (schema instanceof JsonIntegerSchema) node.put("type", "integer");
        else if (schema instanceof JsonNumberSchema) node.put("type", "number");
        else if (schema instanceof JsonBooleanSchema) node.put("type", "boolean");
        else if (schema instanceof JsonNullSchema) node.put("type", "null");
        else throw ModelIntegrationException.capability();
        return node;
    }
}
