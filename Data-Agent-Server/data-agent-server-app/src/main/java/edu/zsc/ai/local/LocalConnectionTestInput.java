package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.dto.request.db.ConnectRequest;

final class LocalConnectionTestInput extends ConnectRequest {
    private Long connectionId;
    private Long expectedVersion;
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long value) { connectionId = value; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long value) { expectedVersion = value; }
    @Override public String toString() { return "LocalConnectionTestInput[redacted]"; }
}
