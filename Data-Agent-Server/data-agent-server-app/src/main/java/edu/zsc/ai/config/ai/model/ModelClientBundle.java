package edu.zsc.ai.config.ai.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

public record ModelClientBundle(ChatModel chatModel, StreamingChatModel streamingChatModel) {}
