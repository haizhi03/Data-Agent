package edu.zsc.ai.domain.service.agent;

import edu.zsc.ai.api.model.request.*;
import edu.zsc.ai.domain.model.entity.db.DbConnection;
import edu.zsc.ai.domain.service.db.ConnectionAccessService;
import edu.zsc.ai.domain.service.db.ConnectionDefinitionReader;
import edu.zsc.ai.plugin.SqlPlugin;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Validate identity and existing access without opening databases or fetching schema contents. */
@Service
@RequiredArgsConstructor
public class ChatReferenceService {
    private static final Set<String> TYPES = Set.of("CONNECTION", "DATABASE", "SCHEMA", "TABLE", "VIEW", "FUNCTION", "PROCEDURE", "TRIGGER", "COLUMN", "INDEX", "KEY");
    private final ConnectionAccessService access;
    private final ConnectionDefinitionReader connections;

    public ChatDefaultTarget normalizeTarget(ChatDefaultTarget source) {
        if (source == null) return new ChatDefaultTarget();
        String catalog = name(source.getCatalogName()), schema = name(source.getSchemaName());
        if (source.getConnectionId() == null) {
            if (catalog != null || schema != null) throw invalid();
            return new ChatDefaultTarget();
        }
        DbConnection connection = readable(source.getConnectionId());
        var plugin = DefaultPluginManager.getInstance().getPluginByDbTypeAndVersion(connection.getDbType(), null);
        if (plugin instanceof SqlPlugin sql) {
            if (!sql.supportDatabase()) catalog = null;
            if (!sql.supportSchema()) schema = null;
        }
        return new ChatDefaultTarget(connection.getId(), catalog, schema);
    }

    public edu.zsc.ai.api.model.request.ChatWorkbenchContext normalizeWorkbenchContext(
            edu.zsc.ai.api.model.request.ChatWorkbenchContext source) {
        if (source == null) return null;
        return new edu.zsc.ai.api.model.request.ChatWorkbenchContext(source.documentId(), source.documentName(),
                source.documentVersion(), source.sql(), source.selected(), source.truncated(), normalizeTarget(source.target()));
    }

    public ChatExecutionReference normalizeExecutionReference(ChatExecutionReference source) {
        if (source == null) return null;
        var target = normalizeTarget(source.target());
        if (target.getConnectionId() == null) throw invalid();
        return new ChatExecutionReference(source.executionId(), source.statementIndex(), source.sql(), target,
                source.documentId(), source.documentName(), source.documentVersion(), source.truncated(), source.result());
    }

    public List<ChatUserMention> validateMentions(String message, List<ChatUserMention> mentions) {
        if (mentions == null) return List.of();
        if (mentions.size() > 32) throw invalid();
        Map<String, ChatUserMention> result = new LinkedHashMap<>();
        // Match longest tokens first: @orders must not consume part of @orders/archive.
        List<ChatUserMention> ordered = new ArrayList<>(mentions);
        if (ordered.stream().anyMatch(Objects::isNull)) throw invalid();
        ordered.sort(Comparator.comparingInt((ChatUserMention m) -> m.getToken() == null ? 0 : m.getToken().length()).reversed());
        List<int[]> occupied = new ArrayList<>();
        Set<String> seenTokens = new HashSet<>();
        for (ChatUserMention source : ordered) {
            String token = source.getToken();
            if (token == null || !token.startsWith("@") || token.length() > 2048) throw invalid();
            if (!seenTokens.add(token)) throw invalid();
            var matcher = Pattern.compile("(?<![^\\s(\\[{\"'`])" + Pattern.quote(token) + "(?=$|[\\s.,!?;:)\\]}\"'`])").matcher(message);
            boolean present = false;
            while (matcher.find()) {
                int start = matcher.start(), end = matcher.end();
                if (occupied.stream().noneMatch(range -> start < range[1] && end > range[0])) {
                    present = true; occupied.add(new int[]{start, end});
                }
            }
            if (!present) continue; // deleted tokens never become hidden model references
            String type = source.getObjectType();
            if (type == null || !TYPES.contains(type)) throw invalid();
            DbConnection connection = readable(source.getConnectionId());
            var target = normalizeTarget(new ChatDefaultTarget(connection.getId(), source.getCatalogName(), source.getSchemaName()));
            String object = name(source.getObjectName());
            if (type.equals("CONNECTION")) { target.setCatalogName(null); target.setSchemaName(null); object = null; }
            else if (type.equals("DATABASE")) {
                if (target.getCatalogName() == null) throw invalid();
                target.setSchemaName(null); object = null;
            } else if (type.equals("SCHEMA")) {
                if (target.getSchemaName() == null) throw invalid();
                object = null;
            } else {
                if (object == null) throw invalid();
                var plugin = DefaultPluginManager.getInstance().getPluginByDbTypeAndVersion(connection.getDbType(), null);
                if (plugin instanceof SqlPlugin sql && ((sql.supportDatabase() && target.getCatalogName() == null)
                        || (sql.supportSchema() && target.getSchemaName() == null))) throw invalid();
            }
            var validated = new ChatUserMention(token, type, connection.getId(), connection.getName(),
                    target.getCatalogName(), target.getSchemaName(), object);
            if (result.putIfAbsent(token, validated) != null) throw invalid();
        }
        return List.copyOf(result.values());
    }

    private DbConnection readable(Long id) {
        if (id == null || id <= 0) throw invalid();
        access.assertReadable(id);
        DbConnection connection = connections.getById(id);
        if (connection == null) throw invalid();
        return connection;
    }
    private static String name(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_REFERENCE_OR_TARGET");
    }
}
