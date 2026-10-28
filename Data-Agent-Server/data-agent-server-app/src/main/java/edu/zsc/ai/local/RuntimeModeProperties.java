package edu.zsc.ai.local;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reject obsolete server mode and typos rather than silently selecting another composition root. */
@ConfigurationProperties("app.runtime")
public record RuntimeModeProperties(Mode mode) {
    public RuntimeModeProperties { mode = mode == null ? Mode.LOCAL : mode; }
    public enum Mode { LOCAL }
}
