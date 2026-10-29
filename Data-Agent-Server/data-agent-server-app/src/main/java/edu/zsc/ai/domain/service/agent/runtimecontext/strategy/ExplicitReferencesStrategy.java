package edu.zsc.ai.domain.service.agent.runtimecontext.strategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import edu.zsc.ai.api.model.request.ChatUserMention;
import edu.zsc.ai.common.constant.PromptConstant;
import edu.zsc.ai.domain.service.agent.prompt.PromptFormatSupport;
import edu.zsc.ai.domain.service.agent.runtimecontext.AbstractRuntimeContextHandler;
import edu.zsc.ai.domain.service.agent.runtimecontext.RuntimeContextAssemblyContext;
import edu.zsc.ai.domain.service.agent.runtimecontext.RuntimeContextSection;

@Component
public class ExplicitReferencesStrategy extends AbstractRuntimeContextHandler {

    @Override
    protected RuntimeContextSection targetSection() {
        return RuntimeContextSection.EXPLICIT_REFERENCES;
    }

    @Override
    protected String buildContent(RuntimeContextAssemblyContext context) {
        return renderReferences(context.getUserMentions(), context.getLanguage());
    }

    public String renderReferences(List<ChatUserMention> references, String language) {
        List<ChatUserMention> mentions = references.stream()
                .filter(Objects::nonNull)
                .filter(mention -> StringUtils.isNotBlank(mention.getObjectType()))
                .filter(mention -> StringUtils.isNotBlank(mention.getConnectionName()))
                .toList();

        if (mentions.isEmpty()) {
            return PromptFormatSupport.renderBlock(
                    language,
                    "本轮用户显式引用：",
                    "本轮用户显式引用：",
                    List.of(PromptConstant.NONE));
        }

        return PromptFormatSupport.renderBlock(
                language,
                "本轮用户显式引用：",
                "本轮用户显式引用：",
                mentions.stream()
                        .map(this::toPayload)
                        .map(this::toLine)
                        .toList());
    }

    private Map<String, Object> toPayload(ChatUserMention mention) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", mention.getToken());
        payload.put("objectType", mention.getObjectType());
        payload.put("connectionId", mention.getConnectionId());
        payload.put("connectionName", mention.getConnectionName());
        payload.put("catalogName", mention.getCatalogName());
        payload.put("schemaName", mention.getSchemaName());
        payload.put("objectName", mention.getObjectName());
        return payload;
    }

    private String toLine(Map<String, Object> payload) {
        return "token: " + StringUtils.defaultString((String) payload.get("token"))
                + "; 对象类型: " + StringUtils.defaultString((String) payload.get("objectType"))
                + "; 连接: " + StringUtils.defaultString((String) payload.get("connectionName"))
                + " (id=" + payload.get("connectionId") + ")"
                + "; catalog: " + StringUtils.defaultString((String) payload.get("catalogName"))
                + "; schema: " + StringUtils.defaultString((String) payload.get("schemaName"))
                + "; 对象: " + StringUtils.defaultString((String) payload.get("objectName"));
    }
}
