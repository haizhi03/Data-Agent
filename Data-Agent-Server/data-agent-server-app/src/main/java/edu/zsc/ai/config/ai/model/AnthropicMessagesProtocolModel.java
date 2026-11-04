package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.*;

final class AnthropicMessagesProtocolModel extends JsonProtocolChatModel {
    AnthropicMessagesProtocolModel(ResolvedModelConfig config, CredentialService credentials, HttpClient http) { super(config, credentials, http); }
    @Override String resource() { return "messages"; }

    @Override ObjectNode encode(ChatRequest request, boolean stream) {
        var body = ProtocolJson.object().put("model", config.modelId()).put("stream", stream).put("max_tokens", config.options().maxOutputTokens());
        if (config.options().temperature() != null) body.put("temperature", config.options().temperature());
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            ArrayNode stop = body.putArray("stop_sequences"); request.stopSequences().forEach(stop::add);
        }
        var thinking = body.putObject("thinking");
        if (config.options().thinkingMode() == ENABLED) thinking.put("type", "enabled").put("budget_tokens", config.options().thinkingBudget());
        else thinking.put("type", config.options().thinkingMode() == ADAPTIVE ? "adaptive" : "disabled");
        ArrayNode system = ProtocolJson.array(), messages = body.putArray("messages");
        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage s) system.add(ProtocolJson.object().put("type", "text").put("text", s.text()));
            else if (message instanceof UserMessage user) {
                add(messages, "user", ProtocolJson.array().add(ProtocolJson.object().put("type", "text").put("text", ProtocolResponses.userText(user))));
            } else if (message instanceof AiMessage ai) {
                ArrayNode blocks = ProtocolStateCodec.blocks(ai, config);
                if (blocks == null) {
                    blocks = ProtocolJson.array();
                    if (ai.text() != null && !ai.text().isEmpty()) blocks.add(ProtocolJson.object().put("type", "text").put("text", ai.text()));
                    for (var call : ai.toolExecutionRequests()) {
                        JsonNode input = ProtocolJson.parse(call.arguments());
                        if (!input.isObject()) throw ProtocolJson.invalid();
                        blocks.add(ProtocolJson.object().put("type", "tool_use").put("id", call.id()).put("name", call.name()).set("input", input));
                    }
                }
                if (!blocks.isEmpty()) add(messages, "assistant", blocks);
            } else if (message instanceof ToolExecutionResultMessage result) {
                if (result.text() == null) throw ProtocolJson.invalid();
                add(messages, "user", ProtocolJson.array().add(ProtocolJson.object().put("type", "tool_result")
                        .put("tool_use_id", result.id()).put("content", result.text()).put("is_error", Boolean.TRUE.equals(result.isError()))));
            } else throw ModelIntegrationException.capability();
        }
        if (!system.isEmpty()) body.set("system", system);
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (var tool : request.toolSpecifications()) {
                var value = ProtocolJson.object().put("name", tool.name());
                if (tool.description() != null) value.put("description", tool.description());
                value.set("input_schema", ToolSchemaMapper.map(tool.parameters())); tools.add(value);
            }
            if (request.toolChoice() == ToolChoice.REQUIRED && config.options().thinkingMode() != DISABLED) throw ModelIntegrationException.capability();
            body.putObject("tool_choice").put("type", request.toolChoice() == ToolChoice.REQUIRED ? "any" : request.toolChoice() == ToolChoice.NONE ? "none" : "auto");
        } else if (request.toolChoice() == ToolChoice.REQUIRED) throw ModelIntegrationException.capability();
        return body;
    }

    private static void add(ArrayNode messages, String role, ArrayNode blocks) {
        JsonNode previous = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (previous != null && role.equals(previous.path("role").asText())) ((ArrayNode) previous.path("content")).addAll(blocks);
        else messages.add(ProtocolJson.object().put("role", role).set("content", blocks));
    }

    @Override ChatResponse decode(JsonNode response) {
        if ("error".equals(response.path("type").asText())) throw ProtocolErrors.from(response.path("error"));
        if (!"message".equals(response.path("type").asText()) || !"assistant".equals(response.path("role").asText()) || !response.path("content").isArray()) throw ProtocolJson.invalid();
        String reason = ProtocolJson.text(response, "stop_reason");
        if ("max_tokens".equals(reason) || "model_context_window_exceeded".equals(reason)) throw new ModelIntegrationException("MODEL_OUTPUT_TRUNCATED", HttpStatus.BAD_GATEWAY);
        if ("refusal".equals(reason)) throw new ModelIntegrationException("MODEL_REFUSED", HttpStatus.BAD_GATEWAY);
        if (!Set.of("end_turn", "tool_use", "stop_sequence").contains(reason)) throw ProtocolJson.invalid();
        ArrayNode content = (ArrayNode) response.get("content");
        StringBuilder text = new StringBuilder(); List<ToolExecutionRequest> calls = new ArrayList<>();
        for (JsonNode block : content) {
            switch (ProtocolJson.text(block, "type")) {
                case "text" -> text.append(ProtocolJson.string(block, "text"));
                case "tool_use" -> {
                    if (!block.path("input").isObject()) throw ProtocolJson.invalid();
                    calls.add(ToolExecutionRequest.builder().id(ProtocolJson.text(block, "id")).name(ProtocolJson.text(block, "name"))
                            .arguments(block.get("input").toString()).build());
                }
                case "thinking" -> { ProtocolJson.string(block, "thinking"); ProtocolJson.text(block, "signature"); }
                case "redacted_thinking" -> ProtocolJson.text(block, "data");
                default -> throw ModelIntegrationException.capability();
            }
        }
        ProtocolResponses.validateCalls(calls);
        if (calls.isEmpty() == "tool_use".equals(reason) || (text.isEmpty() && calls.isEmpty())) throw ProtocolJson.invalid();
        var ai = AiMessage.builder().text(text.isEmpty() ? null : text.toString()).toolExecutionRequests(calls).build();
        return ChatResponse.builder().id(ProtocolJson.text(response, "id")).modelName(response.path("model").asText(config.modelId()))
                .aiMessage(ProtocolStateCodec.attach(ai, config, content)).finishReason(calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_EXECUTION)
                .tokenUsage(ProtocolResponses.usage(response.path("usage"), "input_tokens", "output_tokens")).build();
    }

    @Override StreamDecoder streamDecoder(StreamingChatResponseHandler handler) {
        return new StreamDecoder() {
            private ObjectNode message;
            private final Map<Integer, ObjectNode> blocks = new TreeMap<>();
            private final Map<Integer, StringBuilder> inputs = new HashMap<>();
            private final Set<Integer> closed = new HashSet<>();
            private final ObjectNode usage = ProtocolJson.object();
            public void event(JsonNode event) {
                String type = ProtocolJson.text(event, "type");
                switch (type) {
                    case "ping" -> { }
                    case "error" -> throw ProtocolErrors.from(event.path("error"));
                    case "message_start" -> {
                        if (message != null || !(event.get("message") instanceof ObjectNode initial)
                                || !initial.path("content").isArray() || !initial.path("content").isEmpty()) throw ProtocolJson.invalid();
                        message = initial.deepCopy(); mergeUsage(initial.path("usage"));
                    }
                    case "content_block_start" -> {
                        requireMessage(); int index = index(event);
                        if (!(event.get("content_block") instanceof ObjectNode block) || blocks.putIfAbsent(index, block.deepCopy()) != null) throw ProtocolJson.invalid();
                        if (!Set.of("text", "thinking", "redacted_thinking", "tool_use").contains(block.path("type").asText())) throw ModelIntegrationException.capability();
                        if ("text".equals(block.path("type").asText()) && !block.path("text").asText().isEmpty()) handler.onPartialResponse(ProtocolJson.string(block, "text"));
                    }
                    case "content_block_delta" -> {
                        int index = index(event); ObjectNode block = active(index); JsonNode delta = event.path("delta");
                        String deltaType = ProtocolJson.text(delta, "type");
                        switch (deltaType) {
                            case "text_delta" -> { requireType(block, "text"); String text = ProtocolJson.string(delta, "text"); append(block, "text", text); handler.onPartialResponse(text); }
                            case "thinking_delta" -> { requireType(block, "thinking"); append(block, "thinking", ProtocolJson.string(delta, "thinking")); }
                            case "signature_delta" -> { requireType(block, "thinking"); append(block, "signature", ProtocolJson.string(delta, "signature")); }
                            case "input_json_delta" -> {
                                requireType(block, "tool_use"); String fragment = ProtocolJson.string(delta, "partial_json");
                                if (block.path("input").isObject() && !block.path("input").isEmpty()) throw ProtocolJson.invalid();
                                inputs.computeIfAbsent(index, ignored -> new StringBuilder()).append(fragment);
                                handler.onPartialToolCall(PartialToolCall.builder().index(index).id(block.path("id").asText(null))
                                        .name(block.path("name").asText(null)).partialArguments(fragment).build());
                            }
                            case "citations_delta" -> {
                                requireType(block, "text"); if (!delta.path("citation").isObject()) throw ProtocolJson.invalid();
                                block.withArray("citations").add(delta.get("citation").deepCopy());
                            }
                            default -> throw ProtocolJson.invalid();
                        }
                    }
                    case "content_block_stop" -> {
                        int index = index(event); ObjectNode block = active(index);
                        if (inputs.containsKey(index)) {
                            JsonNode input = ProtocolJson.parse(inputs.get(index).toString()); if (!input.isObject()) throw ProtocolJson.invalid(); block.set("input", input);
                        }
                        closed.add(index);
                    }
                    case "message_delta" -> {
                        requireMessage(); if (closed.size() != blocks.size()) throw ProtocolJson.invalid();
                        JsonNode delta = event.path("delta");
                        if (delta.path("stop_reason").isTextual()) message.put("stop_reason", delta.path("stop_reason").asText());
                        mergeUsage(event.path("usage"));
                    }
                    case "message_stop" -> {
                        requireMessage(); if (closed.size() != blocks.size()) throw ProtocolJson.invalid();
                        ArrayNode content = message.putArray("content");
                        for (int i = 0; i < blocks.size(); i++) { if (!blocks.containsKey(i)) throw ProtocolJson.invalid(); content.add(blocks.get(i)); }
                        message.set("usage", usage.deepCopy()); handler.onCompleteResponse(decode(message));
                    }
                    default -> { /* Forward-compatible non-content metadata events do not advance completion. */ }
                }
            }
            private void requireMessage() { if (message == null) throw ProtocolJson.invalid(); }
            private int index(JsonNode event) { Integer index = ProtocolJson.count(event, "index"); if (index == null || index > 1024) throw ProtocolJson.invalid(); return index; }
            private ObjectNode active(int index) { requireMessage(); if (!blocks.containsKey(index) || closed.contains(index)) throw ProtocolJson.invalid(); return blocks.get(index); }
            private void requireType(JsonNode block, String type) { if (!type.equals(block.path("type").asText())) throw ProtocolJson.invalid(); }
            private void append(ObjectNode block, String field, String text) { block.put(field, block.path(field).asText("") + text); }
            private void mergeUsage(JsonNode incoming) {
                if (incoming.isMissingNode() || incoming.isNull()) return;
                if (!(incoming instanceof ObjectNode object)) throw ProtocolJson.invalid();
                for (String field : List.of("input_tokens", "output_tokens")) {
                    Integer count = ProtocolJson.count(incoming, field), previous = ProtocolJson.count(usage, field);
                    if (count != null && previous != null && count < previous) throw ProtocolJson.invalid();
                }
                usage.setAll(object);
            }
        };
    }
}
