package edu.zsc.ai.api.model.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A conversation fallback, never an authorization or an object binding. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatDefaultTarget {
    private Long connectionId;
    private String catalogName;
    private String schemaName;
}
