package edu.zsc.ai.local;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.context.RequestContextInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** Process-local identity and transport credential. Never expose this bean through HTTP or logging. */
public final class LocalRuntimeIdentity {
    private final UUID runtimeInstanceId = UUID.randomUUID();
    private final String transportToken;

    public LocalRuntimeIdentity() {
        byte[] entropy = new byte[32];
        new SecureRandom().nextBytes(entropy);
        transportToken = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    public UUID runtimeInstanceId() {
        return runtimeInstanceId;
    }

    /** Trusted host only: in-process callers or the opt-in private parent pipe. */
    public String transportTokenForHost() {
        return transportToken;
    }

    boolean authenticates(String authorization) {
        return authorization != null && MessageDigest.isEqual(
                ("Bearer " + transportToken).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8));
    }

    public RequestContextInfo newRequestContext() {
        return RequestContextInfo.builder().userId(1L).workspaceType(WorkspaceTypeEnum.PERSONAL).build();
    }
}
