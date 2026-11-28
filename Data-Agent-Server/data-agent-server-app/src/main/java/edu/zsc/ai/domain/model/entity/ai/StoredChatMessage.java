package edu.zsc.ai.domain.model.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("ai_stored_chat_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private String role;

    private Integer tokenCount;

    private String data;

    /** JSON attachment kept apart from the message text, such as an ER query focus. */
    private String attachment;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
