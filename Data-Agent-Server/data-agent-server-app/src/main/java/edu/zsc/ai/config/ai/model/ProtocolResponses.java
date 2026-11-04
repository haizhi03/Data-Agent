package edu.zsc.ai.config.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.output.TokenUsage;
import java.util.*;

final class ProtocolResponses {
    private ProtocolResponses() {}
    static void validateCalls(List<ToolExecutionRequest> calls) {
        Set<String> seen = new HashSet<>();
        for (var call : calls) {
            if (!seen.add(call.id()) || !ProtocolJson.parse(call.arguments()).isObject()) throw ProtocolJson.invalid();
        }
    }
    static TokenUsage usage(JsonNode usage, String input, String output) {
        Integer in = ProtocolJson.count(usage, input), out = ProtocolJson.count(usage, output);
        Integer total = ProtocolJson.count(usage, "total_tokens");
        if (in != null && out != null) {
            int sum;
            try { sum = Math.addExact(in, out); } catch (ArithmeticException e) { throw ProtocolJson.invalid(); }
            if (total != null && total != sum) throw ProtocolJson.invalid();
            total = sum;
        }
        return in == null && out == null && total == null ? null : new TokenUsage(in, out, total);
    }
    static String userText(dev.langchain4j.data.message.UserMessage user) {
        if (user.contents().stream().anyMatch(c -> !(c instanceof dev.langchain4j.data.message.TextContent))) throw ModelIntegrationException.capability();
        return user.contents().stream().map(c -> ((dev.langchain4j.data.message.TextContent) c).text()).collect(java.util.stream.Collectors.joining("\n"));
    }
}
