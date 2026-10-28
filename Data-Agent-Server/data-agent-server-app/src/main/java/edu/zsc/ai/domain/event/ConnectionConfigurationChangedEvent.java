package edu.zsc.ai.domain.event;

/** No credentials or SQL content. Runtime consumers must stop/revalidate work tied to the previous version. */
public record ConnectionConfigurationChangedEvent(long connectionId, Long version, String reason) { }
