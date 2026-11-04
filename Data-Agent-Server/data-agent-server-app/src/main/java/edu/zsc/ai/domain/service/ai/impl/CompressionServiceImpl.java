package edu.zsc.ai.domain.service.ai.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import edu.zsc.ai.common.enums.ai.PromptEnum;
import edu.zsc.ai.config.ai.AiModelCatalog;
import edu.zsc.ai.config.ai.PromptConfig;
import edu.zsc.ai.config.ai.model.ModelPurpose;
import edu.zsc.ai.config.ai.model.ModelRuntime;
import edu.zsc.ai.config.ai.model.ProtocolHistory;
import edu.zsc.ai.domain.service.ai.CompressionService;
import edu.zsc.ai.domain.service.ai.model.CompressionResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompressionServiceImpl implements CompressionService {

    private final AiModelCatalog aiModelCatalog;
    private final Map<String, ChatModel> chatModelsByName;
    private final ModelRuntime modelRuntime;

    @Override
    public CompressionResult compress(List<ChatMessage> messages) {
        String serializedHistory = ChatMessageSerializer.messagesToJson(ProtocolHistory.forSummary(messages));
        String template = PromptConfig.getPrompt(PromptEnum.COMPRESSION);
        String prompt = String.format(template, serializedHistory);

        ChatModel model = modelRuntime.enabled() ? modelRuntime.chat(ModelPurpose.COMPRESSION)
                : chatModelsByName.get(aiModelCatalog.compressionModelName());
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build();

        ChatResponse response = model.chat(request);
        String summary = response.aiMessage().text();
        Integer totalTokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : null;
        Integer outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : null;

        log.debug("Compressed {} messages into summary ({} chars, totalTokens={}, outputTokens={})",
                messages.size(), summary.length(), totalTokens, outputTokens);
        return new CompressionResult(summary, totalTokens, outputTokens);
    }
}
