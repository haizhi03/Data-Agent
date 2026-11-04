package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Node worker process per temporary conversation. stdout carries only protocol
 * messages (one JSON object per line, size-capped); stderr is drained into diagnostics.
 * Nothing secret is ever written to logs or command line: the API key travels inside
 * the start message on the private stdin pipe.
 */
final class PiWorkerProcess implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PiWorkerProcess.class);

    interface Listener {
        void onMessage(JsonNode message);
        /** Terminal process event: exited, failed to start, or protocol violation. */
        void onGone(String reasonCode);
    }

    private final long conversationId;
    private final ObjectMapper json;
    private final int maxLineBytes;
    private final Listener listener;
    private Process process;
    private BufferedWriter stdin;
    private volatile boolean gone;

    private PiWorkerProcess(long conversationId, ObjectMapper json, int maxLineBytes, Listener listener) {
        this.conversationId = conversationId; this.json = json; this.maxLineBytes = maxLineBytes; this.listener = listener;
    }

    /** Start the worker and wait for its hello message. Throws LocalRequestException on failure. */
    static PiWorkerProcess start(long conversationId, LocalPiProperties properties, ObjectMapper json, Listener listener) {
        var worker = new PiWorkerProcess(conversationId, json, properties.maxLineBytes(), listener);
        var script = properties.resolveWorkerScript();
        if (!java.nio.file.Files.isRegularFile(script)) {
            throw new LocalRequestException(503, "PI_WORKER_NOT_FOUND", "Pi worker 脚本不存在: " + script.getFileName());
        }
        try {
            worker.process = new ProcessBuilder(properties.nodeBinary(), script.toString()).start();
        } catch (java.io.IOException e) {
            log.warn("Pi worker failed to launch: conversationId={}, error={}", conversationId, e.toString());
            throw new LocalRequestException(503, "PI_WORKER_START_FAILED", "Pi worker 启动失败");
        }
        worker.stdin = new BufferedWriter(new OutputStreamWriter(worker.process.getOutputStream(), StandardCharsets.UTF_8));
        worker.drainStderr();
        var hello = new java.util.concurrent.ArrayBlockingQueue<JsonNode>(1);
        var failure = new java.util.concurrent.ArrayBlockingQueue<String>(1);
        Thread reader = new Thread(() -> worker.readStdout(hello, failure), "pi-worker-stdout-" + conversationId);
        reader.setDaemon(true);
        reader.start();
        try {
            var deadline = System.nanoTime() + properties.startupTimeoutMs() * 1_000_000L;
            for (;;) {
                var message = hello.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) {
                    if (!"hello".equals(message.path("type").asText()) || message.path("protocol").asInt() != 1) {
                        worker.close();
                        throw new LocalRequestException(503, "PI_PROTOCOL_ERROR", "Pi worker 协议版本不兼容");
                    }
                    log.info("Pi worker ready: conversationId={}, piAi={}", conversationId, message.path("piAi").asText());
                    return worker;
                }
                var reason = failure.poll();
                if (reason != null || System.nanoTime() > deadline) {
                    worker.close();
                    throw new LocalRequestException(503, "PI_WORKER_START_FAILED", "Pi worker 启动失败");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.close();
            throw new LocalRequestException(503, "PI_WORKER_START_FAILED", "Pi worker 启动被中断");
        }
    }

    boolean isAlive() { return !gone && process != null && process.isAlive(); }

    synchronized void send(Map<String, Object> message) {
        if (!isAlive()) return;
        try {
            stdin.write(json.writeValueAsString(message));
            stdin.write('\n');
            stdin.flush();
        } catch (java.io.IOException e) {
            log.warn("Pi worker stdin write failed: conversationId={}, error={}", conversationId, e.toString());
            markGone("PI_WORKER_EXIT");
        }
    }

    private void readStdout(java.util.concurrent.BlockingQueue<JsonNode> hello, java.util.concurrent.BlockingQueue<String> failure) {
        try (var reader = new BoundedLineReader(process.getInputStream(), maxLineBytes)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode message;
                try {
                    message = json.readTree(line);
                } catch (Exception e) {
                    log.warn("Pi worker emitted non-JSON line: conversationId={}", conversationId);
                    continue;
                }
                if (!gone && "hello".equals(message.path("type").asText()) && hello.remainingCapacity() > 0) {
                    hello.offer(message);
                    continue;
                }
                listener.onMessage(message);
            }
            markGone("PI_WORKER_EXIT");
        } catch (BoundedLineReader.LineTooLongException e) {
            log.warn("Pi worker protocol violation: conversationId={}, error={}", conversationId, e.getMessage());
            markGone("PI_PROTOCOL_ERROR");
        } catch (Exception e) {
            if (!gone) {
                log.warn("Pi worker stdout failed: conversationId={}, error={}", conversationId, e.toString());
                markGone("PI_WORKER_EXIT");
            }
        } finally {
            failure.offer("gone");
        }
    }

    private void drainStderr() {
        Thread drainer = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("pi-worker[{}] {}", conversationId, line.length() > 500 ? line.substring(0, 500) : line);
                }
            } catch (Exception ignored) { /* process gone */ }
        }, "pi-worker-stderr-" + conversationId);
        drainer.setDaemon(true);
        drainer.start();
    }

    private void markGone(String reasonCode) {
        synchronized (this) {
            if (gone) return;
            gone = true;
        }
        if (process != null) process.destroyForcibly();
        listener.onGone(reasonCode);
    }

    @Override public void close() {
        synchronized (this) {
            if (gone && (process == null || !process.isAlive())) return;
            gone = true;
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /** Size-capped line reader: a hostile or broken peer cannot exhaust memory. */
    static final class BoundedLineReader implements AutoCloseable {
        static final class LineTooLongException extends RuntimeException {
            LineTooLongException(int max) { super("line exceeds " + max + " bytes"); }
        }
        private final InputStreamReader reader;
        private final int maxBytes;
        private final StringBuilder pending = new StringBuilder();
        BoundedLineReader(java.io.InputStream stream, int maxBytes) {
            this.reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            this.maxBytes = maxBytes;
        }
        String readLine() throws java.io.IOException {
            var buffer = new char[8192];
            for (;;) {
                int newline = pending.indexOf("\n");
                if (newline >= 0) {
                    String line = pending.substring(0, newline);
                    pending.delete(0, newline + 1);
                    return line;
                }
                int read = reader.read(buffer);
                if (read < 0) return pending.isEmpty() ? null : pending.toString();
                pending.append(buffer, 0, read);
                if (pending.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new LineTooLongException(maxBytes);
            }
        }
        @Override public void close() throws java.io.IOException { reader.close(); }
    }
}
