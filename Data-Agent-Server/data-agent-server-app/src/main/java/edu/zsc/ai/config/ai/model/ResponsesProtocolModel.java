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
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.ENABLED;

final class ResponsesProtocolModel extends JsonProtocolChatModel {
    ResponsesProtocolModel(ResolvedModelConfig config, CredentialService credentials, HttpClient http) { super(config, credentials, http); }
    @Override String resource() { return "responses"; }

    @Override ObjectNode encode(ChatRequest request, boolean stream) {
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) throw ModelIntegrationException.capability();
        var body = ProtocolJson.object().put("model", config.modelId()).put("stream", stream).put("store", false)
                .put("max_output_tokens", config.options().maxOutputTokens());
        body.putArray("include").add("reasoning.encrypted_content");
        if (config.options().temperature() != null) body.put("temperature", config.options().temperature());
        if (config.options().thinkingMode() == ENABLED) {
            body.putObject("reasoning").put("effort", config.options().reasoningEffort() == null ? "medium" : config.options().reasoningEffort());
        }
        ArrayNode input = body.putArray("input");
        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage system) input.add(ProtocolJson.object().put("role", "system").put("content", system.text()));
            else if (message instanceof UserMessage user) {
                input.add(ProtocolJson.object().put("role", "user").put("content", ProtocolResponses.userText(user)));
            } else if (message instanceof AiMessage ai) {
                ArrayNode raw = ProtocolStateCodec.blocks(ai, config);
                if (raw != null) input.addAll(raw);
                else {
                    if (ai.text() != null && !ai.text().isEmpty()) input.add(ProtocolJson.object().put("role", "assistant").put("content", ai.text()));
                    for (var call : ai.toolExecutionRequests()) input.add(ProtocolJson.object().put("type", "function_call")
                            .put("call_id", call.id()).put("name", call.name()).put("arguments", call.arguments()));
                }
            } else if (message instanceof ToolExecutionResultMessage result) {
                if (result.text() == null) throw ProtocolJson.invalid();
                String value = Boolean.TRUE.equals(result.isError()) ? ProtocolJson.object().put("is_error", true).put("result", result.text()).toString() : result.text();
                input.add(ProtocolJson.object().put("type", "function_call_output").put("call_id", result.id()).put("output", value));
            }
            else throw ModelIntegrationException.capability();
        }
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (var tool : request.toolSpecifications()) {
                var value = ProtocolJson.object().put("type", "function").put("name", tool.name()).put("strict", false);
                if (tool.description() != null) value.put("description", tool.description());
                value.set("parameters", ToolSchemaMapper.map(tool.parameters())); tools.add(value);
            }
            body.put("tool_choice", request.toolChoice() == ToolChoice.REQUIRED ? "required" : request.toolChoice() == ToolChoice.NONE ? "none" : "auto");
        } else if (request.toolChoice() == ToolChoice.REQUIRED) throw ModelIntegrationException.capability();
        return body;
    }

    @Override ChatResponse decode(JsonNode response) {
        String status = ProtocolJson.text(response, "status");
        if ("incomplete".equals(status)) throw ProtocolErrors.incomplete(response.path("incomplete_details"));
        if ("failed".equals(status)) throw ProtocolErrors.from(response.path("error"));
        if (!"completed".equals(status) || !response.path("output").isArray()) throw ProtocolJson.invalid();
        ArrayNode output = (ArrayNode) response.get("output");
        StringBuilder text = new StringBuilder();
        List<ToolExecutionRequest> calls = new ArrayList<>();
        for (JsonNode item : output) {
            String itemStatus = item.path("status").asText("completed");
            if (!"completed".equals(itemStatus)) throw ProtocolJson.invalid();
            switch (ProtocolJson.text(item, "type")) {
                case "message" -> {
                    if (!"assistant".equals(item.path("role").asText()) || !item.path("content").isArray()) throw ProtocolJson.invalid();
                    for (JsonNode content : item.path("content")) {
                        String type = ProtocolJson.text(content, "type");
                        if ("refusal".equals(type)) throw new ModelIntegrationException("MODEL_REFUSED", HttpStatus.BAD_GATEWAY);
                        if (!"output_text".equals(type) || !content.path("text").isTextual()) throw ProtocolJson.invalid();
                        text.append(content.path("text").asText());
                    }
                }
                case "function_call" -> calls.add(ToolExecutionRequest.builder().id(ProtocolJson.text(item, "call_id"))
                        .name(ProtocolJson.text(item, "name")).arguments(ProtocolJson.text(item, "arguments")).build());
                case "reasoning" -> {
                    if (!item.path("encrypted_content").isTextual() || item.path("encrypted_content").asText().isBlank()) throw ProtocolJson.incompatible();
                }
                default -> throw ModelIntegrationException.capability(); // Built-in server tools and non-text modalities are not enabled.
            }
        }
        ProtocolResponses.validateCalls(calls);
        if (text.isEmpty() && calls.isEmpty()) throw ProtocolJson.invalid();
        AiMessage ai = AiMessage.builder().text(text.isEmpty() ? null : text.toString()).toolExecutionRequests(calls).build();
        JsonNode usage = response.path("usage");
        return ChatResponse.builder().id(ProtocolJson.text(response, "id")).modelName(response.path("model").asText(config.modelId()))
                .aiMessage(ProtocolStateCodec.attach(ai, config, output)).finishReason(calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_EXECUTION)
                .tokenUsage(ProtocolResponses.usage(usage, "input_tokens", "output_tokens")).build();
    }

    @Override StreamDecoder streamDecoder(StreamingChatResponseHandler handler) {
        return new StreamDecoder() {
            private final Map<Integer, ObjectNode> items = new TreeMap<>();
            private final Set<Integer> done = new HashSet<>();
            private final Map<Integer, StringBuilder> arguments = new HashMap<>();
            private final Map<String, StringBuilder> texts = new HashMap<>();
            private String responseId;
            private long sequence = -1;
            public void event(JsonNode event) {
                JsonNode seq = event.path("sequence_number");
                if (!seq.isMissingNode()) {
                    if (!seq.isIntegralNumber() || seq.longValue() <= sequence) throw ProtocolJson.invalid();
                    sequence = seq.longValue();
                }
                String type = ProtocolJson.text(event, "type");
                switch (type) {
                    case "response.created" -> {
                        if (responseId != null) throw ProtocolJson.invalid();
                        responseId = ProtocolJson.text(event.path("response"), "id");
                    }
                    case "response.output_item.added" -> {
                        if (responseId == null) throw ProtocolJson.invalid();
                        int index = index(event); ObjectNode item = object(event.get("item"));
                        ProtocolJson.text(item, "id");
                        if (items.putIfAbsent(index, item.deepCopy()) != null) throw ProtocolJson.invalid();
                    }
                    case "response.function_call_arguments.delta" -> {
                        int index = index(event); JsonNode item = active(index);
                        if (!"function_call".equals(item.path("type").asText())) throw ProtocolJson.invalid();
                        String delta = ProtocolJson.string(event, "delta");
                        arguments.computeIfAbsent(index, ignored -> new StringBuilder()).append(delta);
                        handler.onPartialToolCall(PartialToolCall.builder().index(index).id(item.path("call_id").asText(null))
                                .name(item.path("name").asText(null)).partialArguments(delta).build());
                    }
                    case "response.output_text.delta" -> {
                        int index = index(event); JsonNode item = active(index);
                        if (!"message".equals(item.path("type").asText())) throw ProtocolJson.invalid();
                        Integer contentIndex = ProtocolJson.count(event, "content_index"); if (contentIndex == null) throw ProtocolJson.invalid();
                        String delta = ProtocolJson.string(event, "delta");
                        texts.computeIfAbsent(index + "/" + contentIndex, ignored -> new StringBuilder()).append(delta);
                        handler.onPartialResponse(delta);
                    }
                    case "response.output_item.done" -> {
                        int index = index(event); JsonNode previous = active(index); ObjectNode item = object(event.get("item"));
                        if (!Objects.equals(previous.path("id").asText(), item.path("id").asText()) || !previous.path("type").equals(item.path("type"))) throw ProtocolJson.invalid();
                        if (previous.path("call_id").isTextual() && !previous.path("call_id").equals(item.path("call_id"))) throw ProtocolJson.invalid();
                        if (arguments.containsKey(index) && !arguments.get(index).toString().equals(item.path("arguments").asText())) throw ProtocolJson.invalid();
                        if ("message".equals(item.path("type").asText())) {
                            if (!item.path("content").isArray()) throw ProtocolJson.invalid();
                            for (int i = 0; i < item.path("content").size(); i++) {
                                JsonNode content = item.path("content").get(i);
                                if (!"output_text".equals(content.path("type").asText())) continue;
                                String text = ProtocolJson.string(content, "text"); StringBuilder emitted = texts.get(index + "/" + i);
                                if (emitted != null && !emitted.toString().equals(text)) throw ProtocolJson.invalid();
                                if (emitted == null && !text.isEmpty()) handler.onPartialResponse(text);
                            }
                        }
                        items.put(index, item.deepCopy()); done.add(index);
                    }
                    case "response.completed" -> {
                        JsonNode response = event.path("response");
                        if (responseId == null || !responseId.equals(response.path("id").asText()) || done.size() != items.size()
                                || !response.path("output").isArray() || response.path("output").size() != items.size()) throw ProtocolJson.invalid();
                        for (int i = 0; i < response.path("output").size(); i++) if (!response.path("output").get(i).equals(items.get(i))) throw ProtocolJson.invalid();
                        handler.onCompleteResponse(decode(response));
                    }
                    case "response.incomplete" -> throw ProtocolErrors.incomplete(event.path("response").path("incomplete_details"));
                    case "response.failed" -> throw ProtocolErrors.from(event.path("response").path("error"));
                    case "error" -> throw ProtocolErrors.from(event);
                    default -> { /* Public text deltas only; opaque reasoning/signature events are never UI thoughts. */ }
                }
            }
            private int index(JsonNode event) {
                Integer index = ProtocolJson.count(event, "output_index");
                if (index == null || index > 1024) throw ProtocolJson.invalid(); return index;
            }
            private JsonNode active(int index) {
                if (!items.containsKey(index) || done.contains(index)) throw ProtocolJson.invalid(); return items.get(index);
            }
            private ObjectNode object(JsonNode value) { if (!(value instanceof ObjectNode object)) throw ProtocolJson.invalid(); return object; }
        };
    }
}
