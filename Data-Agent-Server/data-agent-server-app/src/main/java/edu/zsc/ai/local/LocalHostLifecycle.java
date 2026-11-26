package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;

/** Opt-in private pipe to the packaged host. EOF also covers a killed host process. */
final class LocalHostLifecycle implements ApplicationListener<ApplicationReadyEvent> {
    private final LocalRuntimeIdentity identity;
    private final ObjectMapper json;
    private final LocalConversationRegistry sessions;
    private final LocalConnectionService connections;

    LocalHostLifecycle(LocalRuntimeIdentity identity, ObjectMapper json,
            LocalConversationRegistry sessions, LocalConnectionService connections) {
        this.identity = identity;
        this.json = json;
        this.sessions = sessions;
        this.connections = connections;
    }

    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        var context = (ServletWebServerApplicationContext) event.getApplicationContext();
        try {
            // The host consumes this line before writing other console lines to its log.
            System.out.println("DATA_AGENT_HOST_READY " + json.writeValueAsString(Map.of(
                    "contractVersion", 1,
                    "apiBaseURL", "http://127.0.0.1:" + context.getWebServer().getPort() + "/api",
                    "runtimeInstanceId", identity.runtimeInstanceId(),
                    "transportToken", identity.transportTokenForHost())));
            System.out.flush();
        } catch (java.io.IOException error) {
            context.close();
            throw new IllegalStateException("Desktop host bootstrap failed");
        }
        var watcher = new Thread(() -> {
            try { while (System.in.read() != -1) { /* Parent keeps the pipe open. */ } }
            catch (java.io.IOException ignored) { /* Broken pipe has the same ownership semantics. */ }
            finally {
                // Tomcat drains requests before destroying beans. Cancel work first so a
                // live JDBC read or model stream cannot hold that drain open until timeout.
                try { sessions.shutdown(); }
                finally {
                    try { connections.close(); }
                    finally { context.close(); }
                }
            }
        }, "desktop-host-pipe");
        watcher.setDaemon(true);
        watcher.start();
    }
}
