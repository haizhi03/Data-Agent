package edu.zsc.ai.config.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import edu.zsc.ai.api.model.request.ErQueryFocus;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.domain.service.agent.ErQueryFocusAttachment;
import edu.zsc.ai.domain.service.agent.prompt.PromptHandleRequest;
import edu.zsc.ai.domain.service.agent.prompt.PromptSectionResult;
import edu.zsc.ai.domain.service.agent.runtimecontext.RuntimeContextAssemblyContext;
import edu.zsc.ai.domain.service.agent.runtimecontext.RuntimeContextHandlerChain;
import edu.zsc.ai.domain.service.agent.runtimecontext.RuntimeContextSection;
import edu.zsc.ai.domain.service.ai.AiMessageService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Adds saved ER attachments to the outgoing model request.
 * The stored user message stays the original text.
 */
@Component
@RequiredArgsConstructor
public class ErQueryFocusModelInjector {

    private final RuntimeContextHandlerChain runtimeContextHandlerChain;
    private final AiMessageService aiMessageService;

    public ChatRequest inject(ChatRequest request) {
        List<ErQueryFocus> focuses = focusesForThisCall();
        if (focuses.isEmpty()) {
            return request;
        }
        PromptSectionResult<RuntimeContextSection> section = runtimeContextHandlerChain.handle(
                new PromptHandleRequest<>(
                        RuntimeContextAssemblyContext.builder()
                                .language(AgentRequestContext.getLanguage())
                                .erFocuses(focuses)
                                .build(),
                        RuntimeContextSection.ER_QUERY_FOCUS));
        if (section == null || StringUtils.isBlank(section.content())) {
            return request;
        }
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage system) {
            messages.set(0, SystemMessage.from(system.text() + "\n\n" + section.content()));
        } else {
            messages.add(0, SystemMessage.from(section.content()));
        }
        return request.toBuilder().messages(messages).build();
    }

    private List<ErQueryFocus> focusesForThisCall() {
        List<ErQueryFocus> focuses = new ArrayList<>(aiMessageService.listErFocuses(RequestContext.getConversationId()));
        if (!AgentRequestContext.hasContext() || AgentRequestContext.get().getErFocus() == null) {
            return focuses;
        }
        ErQueryFocus current = AgentRequestContext.get().getErFocus();
        String currentJson = ErQueryFocusAttachment.write(current);
        boolean alreadyStored = focuses.stream()
                .anyMatch(saved -> currentJson != null && currentJson.equals(ErQueryFocusAttachment.write(saved)));
        if (!alreadyStored) {
            focuses.add(current);
        }
        return focuses;
    }
}
