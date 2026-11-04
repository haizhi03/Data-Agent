package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Synthetic fixtures, containing no real provider data, keys, signatures or encrypted state. */
final class ProtocolFixtures {
    private ProtocolFixtures() {}
    static ObjectNode call(ModelApiStyle style, String suffix, String nonce) {
        String arguments = ProtocolJson.object().put("nonce", nonce).toString();
        if (style == ModelApiStyle.OPENAI_RESPONSES) return ProtocolJson.object().put("type", "function_call").put("id", "fc-" + suffix)
                .put("call_id", "call-" + suffix).put("name", "echo").put("arguments", arguments).put("status", "completed");
        return ProtocolJson.object().put("type", "tool_use").put("id", "call-" + suffix).put("name", "echo").set("input", ProtocolJson.parse(arguments));
    }
    static ArrayNode stateAndCalls(ModelApiStyle style, String nonce, boolean parallel) {
        ArrayNode blocks = ProtocolJson.array();
        if (style == ModelApiStyle.OPENAI_RESPONSES) blocks.add(ProtocolJson.object().put("id", "rs-1").put("type", "reasoning")
                .put("encrypted_content", "fixture-encrypted-state").set("summary", ProtocolJson.array()));
        else {
            blocks.add(ProtocolJson.object().put("type", "thinking").put("thinking", "private-A").put("signature", "fixture-signature-A"));
            blocks.add(ProtocolJson.object().put("type", "redacted_thinking").put("data", "fixture-redacted"));
            blocks.add(ProtocolJson.object().put("type", "thinking").put("thinking", "private-B").put("signature", "fixture-signature-B"));
        }
        blocks.add(call(style, "a", nonce));
        if (parallel) blocks.add(call(style, "b", nonce));
        return blocks;
    }
    static ArrayNode text(ModelApiStyle style, String text) {
        if (style == ModelApiStyle.ANTHROPIC_MESSAGES) return ProtocolJson.array().add(ProtocolJson.object().put("type", "text").put("text", text));
        var item = ProtocolJson.object().put("id", "msg-item").put("type", "message").put("status", "completed").put("role", "assistant");
        item.putArray("content").add(ProtocolJson.object().put("type", "output_text").put("text", text).set("annotations", ProtocolJson.array()));
        return ProtocolJson.array().add(item);
    }
    static ObjectNode response(ModelApiStyle style, ArrayNode content, String id) {
        var response = ProtocolJson.object().put("id", id).put("model", "fixture-model");
        if (style == ModelApiStyle.OPENAI_RESPONSES) response.put("object", "response").put("status", "completed").set("output", content);
        else response.put("type", "message").put("role", "assistant").put("stop_reason",
                content.findValuesAsText("type").contains("tool_use") ? "tool_use" : "end_turn").set("content", content);
        response.putObject("usage").put("input_tokens", 11).put("output_tokens", 7);
        return response;
    }
    static String event(String type, ObjectNode fields) {
        fields.put("type", type); return "event: " + type + "\ndata: " + fields + "\n\n";
    }
    static String stream(ModelApiStyle style, ObjectNode response, boolean terminal) {
        StringBuilder sse = new StringBuilder(": keep-alive\n\n");
        if (style == ModelApiStyle.OPENAI_RESPONSES) {
            sse.append(event("response.created", ProtocolJson.object().set("response", ProtocolJson.object().put("id", response.path("id").asText()).put("status", "in_progress"))));
            JsonNode output = response.path("output");
            Map<Integer, String[]> fragments = new LinkedHashMap<>();
            for (int i = 0; i < output.size(); i++) {
                ObjectNode item = ((ObjectNode) output.get(i)).deepCopy();
                if ("function_call".equals(item.path("type").asText())) {
                    String args = item.path("arguments").asText(); fragments.put(i, new String[]{args.substring(0, 9), args.substring(9)});
                    item.put("arguments", ""); item.remove("call_id"); // Delayed call ID; indices must remain independent.
                } else if ("reasoning".equals(item.path("type").asText())) item.put("encrypted_content", "incomplete-encrypted-state");
                sse.append(event("response.output_item.added", ProtocolJson.object().put("output_index", i).set("item", item)));
            }
            for (int fragment = 0; fragment < 2; fragment++) for (var entry : fragments.entrySet()) sse.append(event("response.function_call_arguments.delta",
                    ProtocolJson.object().put("output_index", entry.getKey()).put("delta", entry.getValue()[fragment])));
            for (int i = 0; i < output.size(); i++) {
                JsonNode item = output.get(i);
                if ("message".equals(item.path("type").asText())) {
                    String text = item.path("content").get(0).path("text").asText();
                    sse.append(event("response.output_text.delta", ProtocolJson.object().put("output_index", i).put("content_index", 0).put("delta", "")));
                    sse.append(event("response.output_text.delta", ProtocolJson.object().put("output_index", i).put("content_index", 0).put("delta", text)));
                }
                sse.append(event("response.output_item.done", ProtocolJson.object().put("output_index", i).set("item", item.deepCopy())));
            }
            if (terminal) sse.append(event("response.completed", ProtocolJson.object().set("response", response.deepCopy())));
        } else {
            ObjectNode initial = response.deepCopy(); initial.set("content", ProtocolJson.array()); initial.putNull("stop_reason");
            ((ObjectNode) initial.get("usage")).put("output_tokens", 0);
            sse.append(event("message_start", ProtocolJson.object().set("message", initial)));
            JsonNode blocks = response.path("content"); Map<Integer, String[]> fragments = new LinkedHashMap<>();
            for (int i = 0; i < blocks.size(); i++) {
                ObjectNode block = ((ObjectNode) blocks.get(i)).deepCopy();
                String type = block.path("type").asText();
                if ("tool_use".equals(type)) { String json = block.get("input").toString(); fragments.put(i, new String[]{json.substring(0, 9), json.substring(9)}); block.set("input", ProtocolJson.object()); }
                if ("thinking".equals(type)) { block.put("thinking", ""); block.put("signature", ""); }
                if ("text".equals(type)) block.put("text", "");
                sse.append(event("content_block_start", ProtocolJson.object().put("index", i).set("content_block", block)));
            }
            for (int fragment = 0; fragment < 2; fragment++) for (var entry : fragments.entrySet()) sse.append(event("content_block_delta", ProtocolJson.object().put("index", entry.getKey())
                    .set("delta", ProtocolJson.object().put("type", "input_json_delta").put("partial_json", entry.getValue()[fragment]))));
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode block = blocks.get(i); String type = block.path("type").asText();
                if ("thinking".equals(type)) {
                    sse.append(event("content_block_delta", ProtocolJson.object().put("index", i).set("delta", ProtocolJson.object().put("type", "thinking_delta").put("thinking", block.path("thinking").asText()))));
                    String signature = block.path("signature").asText();
                    sse.append(event("content_block_delta", ProtocolJson.object().put("index", i).set("delta", ProtocolJson.object().put("type", "signature_delta").put("signature", signature.substring(0, 8)))));
                    sse.append(event("content_block_delta", ProtocolJson.object().put("index", i).set("delta", ProtocolJson.object().put("type", "signature_delta").put("signature", signature.substring(8)))));
                } else if ("text".equals(type)) sse.append(event("content_block_delta", ProtocolJson.object().put("index", i).set("delta", ProtocolJson.object().put("type", "text_delta").put("text", block.path("text").asText()))));
                sse.append(event("content_block_stop", ProtocolJson.object().put("index", i)));
            }
            sse.append(event("message_delta", ProtocolJson.object().set("delta", ProtocolJson.object().put("stop_reason", response.path("stop_reason").asText()))));
            sse.append(event("message_delta", ProtocolJson.object().set("usage", response.path("usage").deepCopy()))); // Usage-only last frame.
            if (terminal) sse.append(event("message_stop", ProtocolJson.object()));
        }
        return sse.toString();
    }
}
