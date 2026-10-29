package edu.zsc.ai.domain.model.dto.response.ai;

import edu.zsc.ai.api.model.request.ChatDefaultTarget;
import edu.zsc.ai.api.model.request.ChatUserMention;

import com.fasterxml.jackson.annotation.JsonFormat;
import edu.zsc.ai.domain.model.dto.response.agent.ChatResponseBlock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One message in conversation history. Matches frontend ChatMessage shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessageResponse {

    private String id;
    private String role;
    private String content;
    private List<ChatUserMention> userMentions;
    private ChatDefaultTarget defaultTarget;
    private List<ChatResponseBlock> blocks;
    private String messageStatus;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
