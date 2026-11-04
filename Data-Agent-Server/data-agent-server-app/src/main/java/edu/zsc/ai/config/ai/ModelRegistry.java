package edu.zsc.ai.config.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class ModelRegistry {

    private final List<ChatModelProvider> providers;

    @Bean
    public Map<String, StreamingChatModel> modelsByName() {
        Map<String, StreamingChatModel> merged = new LinkedHashMap<>();
        for (ChatModelProvider provider : providers) {
            provider.streamingChatModels().forEach((name, model) -> {
                if (merged.putIfAbsent(name, model) != null) throw new IllegalStateException("Duplicate streaming model registration: " + name);
            });
        }
        return Collections.unmodifiableMap(merged);
    }

    @Bean
    public Map<String, ChatModel> chatModelsByName() {
        Map<String, ChatModel> merged = new LinkedHashMap<>();
        for (ChatModelProvider provider : providers) {
            provider.chatModels().forEach((name, model) -> {
                if (merged.putIfAbsent(name, model) != null) throw new IllegalStateException("Duplicate chat model registration: " + name);
            });
        }
        return Collections.unmodifiableMap(merged);
    }
}
