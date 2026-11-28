package edu.zsc.ai.domain.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.zsc.ai.api.model.request.ErQueryFocus;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ErQueryFocusAttachment {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ErQueryFocusAttachment() {
    }

    public static String write(ErQueryFocus focus) {
        if (focus == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(focus);
        } catch (JsonProcessingException e) {
            log.warn("Could not store ER query focus: {}", e.getMessage());
            return null;
        }
    }

    public static ErQueryFocus read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, ErQueryFocus.class);
        } catch (JsonProcessingException e) {
            log.warn("Could not read stored ER query focus: {}", e.getMessage());
            return null;
        }
    }
}
