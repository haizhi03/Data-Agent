package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Protocol negotiation of PiWorkerProcess against real Node fixture processes. No Spring context. */
class PiWorkerProtocolTest {
    @TempDir Path root;
    static final ObjectMapper JSON=new ObjectMapper();

    static boolean nodeAvailable() {
        try { return new ProcessBuilder("node","--version").start().waitFor(5,TimeUnit.SECONDS); }
        catch (Exception e) { return false; }
    }

    private PiWorkerProcess startWorker(Path script) {
        var properties=new LocalPiProperties(true,"node",script,10000,0,3);
        return PiWorkerProcess.start(1,properties,JSON,new PiWorkerProcess.Listener() {
            @Override public void onMessage(com.fasterxml.jackson.databind.JsonNode message) { }
            @Override public void onGone(String reasonCode) { }
        });
    }

    @Test void v1WorkerNegotiatesProtocol1WithoutRecoveredRuns() {
        Assumptions.assumeTrue(nodeAvailable());
        var worker=startWorker(Path.of("src/test/resources/pi-fixture-worker.mjs").toAbsolutePath());
        try {
            assertThat(worker.protocol()).isEqualTo(1);
            assertThat(worker.recoveredRuns()).isEmpty();
        } finally { worker.close(); }
    }

    @Test void v2WorkerNegotiatesProtocol2WithAnEmptyStore() throws Exception {
        Assumptions.assumeTrue(nodeAvailable());
        Path fixture=Path.of("src/test/resources/pi-fixture-worker-v2.mjs").toAbsolutePath();
        Path wrapper=root.resolve("wrapper-v2.mjs");
        Files.writeString(wrapper,"process.env.PI_FIXTURE_V2_STATE = "+JSON.writeValueAsString(root.resolve("state.json").toString())+";\n"
                +"await import("+JSON.writeValueAsString(fixture.toUri().toURL().toString())+");\n");
        var worker=startWorker(wrapper);
        try {
            assertThat(worker.protocol()).isEqualTo(2);
            assertThat(worker.recoveredRuns()).isEmpty();
        } finally { worker.close(); }
    }

    @Test void v2HelloRecoveredRunsAreParsed() throws Exception {
        Assumptions.assumeTrue(nodeAvailable());
        Path script=root.resolve("hello-v2-recovered.mjs");
        Files.writeString(script,"""
                import readline from 'node:readline';
                process.stdout.write(JSON.stringify({type:'hello',protocol:2,piDurable:'1.5.0',piAi:'1.5.0',storage:'sqlite',
                  recoveredRuns:[{sessionId:'conversation-7',runId:'run-1',status:'RUNNING'},{sessionId:'broken'}]})+'\\n');
                readline.createInterface({input:process.stdin}).on('line',()=>{});
                """);
        var worker=startWorker(script);
        try {
            assertThat(worker.protocol()).isEqualTo(2);
            assertThat(worker.recoveredRuns()).containsExactly(
                    new PiWorkerProcess.RecoveredRun("conversation-7","run-1","RUNNING"));
        } finally { worker.close(); }
    }

    @Test void unsupportedProtocolVersionIsRejected() throws Exception {
        Assumptions.assumeTrue(nodeAvailable());
        Path script=root.resolve("hello-v3.mjs");
        Files.writeString(script,"""
                import readline from 'node:readline';
                process.stdout.write(JSON.stringify({type:'hello',protocol:3})+'\\n');
                readline.createInterface({input:process.stdin}).on('line',()=>{});
                """);
        assertThatThrownBy(() -> startWorker(script))
                .isInstanceOf(LocalRequestException.class)
                .extracting(e -> ((LocalRequestException) e).response().reasonCode())
                .isEqualTo("PI_PROTOCOL_ERROR");
    }
}
