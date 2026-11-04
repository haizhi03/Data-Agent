package edu.zsc.ai.local;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Experimental Pi (pi-agent-core) worker integration. Disabled by default; the existing
 * Java assistant stays the default engine. The worker script is resolved explicitly, never
 * downloaded at runtime. Credentials are passed to the worker only through its stdin pipe.
 */
@ConfigurationProperties("app.local.pi")
public record LocalPiProperties(boolean enabled, String nodeBinary, Path workerScript,
                                int startupTimeoutMs, int maxLineBytes, int cancelGraceSeconds) {
    public LocalPiProperties {
        nodeBinary = nodeBinary == null || nodeBinary.isBlank() ? "node" : nodeBinary;
        if (startupTimeoutMs <= 0) startupTimeoutMs = 10000;
        if (maxLineBytes < 4096) maxLineBytes = 262144;
        if (cancelGraceSeconds <= 0) cancelGraceSeconds = 10;
    }

    /** Dev default: pi-worker next to the server module; packaged builds pass an absolute path. */
    public Path resolveWorkerScript() {
        if (workerScript != null) return workerScript.toAbsolutePath().normalize();
        var userDir = Path.of(System.getProperty("user.dir"));
        var candidates = new Path[]{
                userDir.resolve("pi-worker/src/worker.js"),
                userDir.resolve("Data-Agent-Server/pi-worker/src/worker.js"),
                userDir.resolveSibling("pi-worker/src/worker.js")};
        for (Path candidate : candidates) {
            if (java.nio.file.Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return candidates[1].toAbsolutePath().normalize();
    }
}
