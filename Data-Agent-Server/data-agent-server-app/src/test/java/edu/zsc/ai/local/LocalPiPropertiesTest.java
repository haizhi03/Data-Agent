package edu.zsc.ai.local;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LocalPiPropertiesTest {
    @TempDir Path root;

    @Test void resolvesTheIndependentWorkerFromRepositoryServerAndAppDirectories() throws Exception {
        Path script=root.resolve("Data-Agent-Pi-Worker/src/worker.js");
        Files.createDirectories(script.getParent());Files.writeString(script,"// fixture");
        Path server=Files.createDirectory(root.resolve("Data-Agent-Server"));
        Path app=Files.createDirectory(server.resolve("data-agent-server-app"));
        var properties=new LocalPiProperties(true,null,null,0,0,0);
        for(Path directory:new Path[]{root,server,app})
            assertThat(properties.resolveWorkerScript(directory)).isEqualTo(script.toAbsolutePath());
    }

    @Test void anExplicitPackagedWorkerPathTakesPrecedence() {
        Path script=root.resolve("packaged/pi/worker.js");
        var properties=new LocalPiProperties(true,null,script,0,0,0);
        assertThat(properties.resolveWorkerScript(root)).isEqualTo(script.toAbsolutePath());
    }

    @Test void missingWorkerReturnsTheNewLocationForTheStartupError() {
        var properties=new LocalPiProperties(true,null,null,0,0,0);
        assertThat(properties.resolveWorkerScript(root)).isEqualTo(root.resolve("Data-Agent-Pi-Worker/src/worker.js").toAbsolutePath());
    }
}
