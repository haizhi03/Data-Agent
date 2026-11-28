package edu.zsc.ai.domain.service.agent.runtimecontext.strategy;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import edu.zsc.ai.api.model.request.ErQueryFocus;
import edu.zsc.ai.domain.service.agent.ErQueryFocusMessage;
import edu.zsc.ai.domain.service.agent.runtimecontext.AbstractRuntimeContextHandler;
import edu.zsc.ai.domain.service.agent.runtimecontext.RuntimeContextAssemblyContext;
import edu.zsc.ai.domain.service.agent.runtimecontext.RuntimeContextSection;

@Component
public class ErQueryFocusStrategy extends AbstractRuntimeContextHandler {

    private final MessageSource messageSource;

    public ErQueryFocusStrategy(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    protected RuntimeContextSection targetSection() {
        return RuntimeContextSection.ER_QUERY_FOCUS;
    }

    @Override
    protected String buildContent(RuntimeContextAssemblyContext context) {
        List<ErQueryFocus> focuses = context.getErFocuses();
        if (focuses == null || focuses.isEmpty()) {
            return "";
        }
        Locale locale = localeOf(context.getLanguage());
        StringBuilder block = new StringBuilder();
        for (ErQueryFocus focus : focuses) {
            String rendered = ErQueryFocusMessage.contextBlock(focus, messageSource, locale);
            if (rendered.isBlank()) {
                continue;
            }
            if (!block.isEmpty()) {
                block.append("\n\n");
            }
            block.append(rendered);
        }
        return block.toString();
    }

    static Locale localeOf(String language) {
        if (language == null || language.isBlank()) {
            return Locale.US;
        }
        String tag = language.replace('_', '-');
        if (tag.equalsIgnoreCase("en")) {
            return Locale.US;
        }
        if (tag.equalsIgnoreCase("zh")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return Locale.forLanguageTag(tag);
    }
}
