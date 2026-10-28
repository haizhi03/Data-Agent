package edu.zsc.ai.local;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.zsc.ai.domain.model.dto.request.db.ConnectionCreateRequest;

/** Local additions do not change the legacy server request contract. */
@JsonIgnoreProperties(ignoreUnknown = false)
final class LocalConnectionInput extends ConnectionCreateRequest {
    private Long expectedVersion;
    private CredentialAction credentialAction = CredentialAction.KEEP;
    public enum CredentialAction { KEEP, REMOVE }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long value) { expectedVersion = value; }
    public CredentialAction getCredentialAction() { return credentialAction; }
    public void setCredentialAction(CredentialAction value) { credentialAction = value; }
    @Override public String toString() { return "LocalConnectionInput[redacted]"; }
}
