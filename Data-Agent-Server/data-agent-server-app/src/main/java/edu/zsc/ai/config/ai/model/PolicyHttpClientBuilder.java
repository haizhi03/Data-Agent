package edu.zsc.ai.config.ai.model;

import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;
import edu.zsc.ai.context.AgentRequestContext;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.io.FilterInputStream;
import java.io.InputStream;
import okhttp3.*;
import org.springframework.http.HttpStatus;

/** Dedicated transport: approved DNS addresses are used by the socket; redirects are never followed. */
final class PolicyHttpClientBuilder implements HttpClientBuilder {
    private final ResolvedModelConfig config;
    private final Supplier<OkHttpClient.Builder> clients;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(120);
    PolicyHttpClientBuilder(ResolvedModelConfig config) { this(config, OkHttpClient.Builder::new); }
    PolicyHttpClientBuilder(ResolvedModelConfig config, Supplier<OkHttpClient.Builder> clients) { this.config = config; this.clients = clients; }
    public Duration connectTimeout() { return connectTimeout; }
    public HttpClientBuilder connectTimeout(Duration value) { connectTimeout = value; return this; }
    public Duration readTimeout() { return readTimeout; }
    public HttpClientBuilder readTimeout(Duration value) { readTimeout = value; return this; }
    public HttpClient build() {
        OkHttpClient client = clients.get()
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(connectTimeout).readTimeout(readTimeout).callTimeout(readTimeout)
                .dns(host -> {
                    if (!host.equalsIgnoreCase(config.endpoint().getHost())) throw new UnknownHostException("Unapproved host");
                    return ModelEndpointPolicy.resolve(host, config.allowedPrivateHosts());
                }).build();
        return new HttpClient() {
            private Call call(HttpRequest request) {
                URI target = URI.create(request.url());
                if (!target.getHost().equalsIgnoreCase(config.endpoint().getHost())
                        || !target.getScheme().equals(config.endpoint().getScheme())
                        || target.getPort() != config.endpoint().getPort()
                        || !target.getPath().startsWith(config.endpoint().getPath())) throw ModelIntegrationException.unavailable();
                Request.Builder builder = new Request.Builder().url(request.url());
                request.headers().forEach((name, values) -> values.forEach(v -> builder.addHeader(name, v)));
                builder.method(request.method().name(), RequestBody.create(request.body() == null ? "" : request.body(),
                        MediaType.get("application/json; charset=utf-8")));
                return client.newCall(builder.build());
            }
            public SuccessfulHttpResponse execute(HttpRequest request) {
                Call call = call(request);
                try (var registration = track(call); Response response = call.execute()) {
                    check(response);
                    return result(response, response.body() == null ? "" : new String(bounded(response.body().byteStream()).readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) { throw failure(e); }
            }
            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                Call call = call(request);
                var registration = track(call);
                call.enqueue(new Callback() {
                    public void onFailure(Call call, IOException e) { registration.close(); listener.onError(failure(e)); }
                    public void onResponse(Call call, Response response) {
                        try (registration; response) {
                            check(response);
                            listener.onOpen(result(response, null));
                            if (response.body() == null) throw failure();
                            parser.parse(bounded(response.body().byteStream()), listener);
                            listener.onClose();
                        } catch (Exception e) { listener.onError(e instanceof ModelIntegrationException ? e : failure()); }
                    }
                });
            }
            private ModelRunSnapshot.CancellationRegistration track(Call call) {
                var context = AgentRequestContext.snapshot();
                return context != null && context.getModelRunSnapshot() != null
                        ? context.getModelRunSnapshot().track(call::cancel) : () -> {};
            }
            private SuccessfulHttpResponse result(Response r, String body) {
                return SuccessfulHttpResponse.builder().statusCode(r.code()).headers(r.headers().toMultimap()).body(body).build();
            }
            private void check(Response r) {
                if (!r.isSuccessful()) {
                    String code = switch (r.code()) {
                        case 401, 403 -> "MODEL_AUTH_FAILED";
                        case 429 -> "MODEL_RATE_LIMITED";
                        case 408, 504 -> "MODEL_UPSTREAM_TIMEOUT";
                        default -> "MODEL_PROTOCOL_ERROR";
                    };
                    throw new ModelIntegrationException(code, HttpStatus.BAD_GATEWAY);
                }
            }
            private ModelIntegrationException failure() {
                return new ModelIntegrationException("MODEL_UPSTREAM_UNAVAILABLE", HttpStatus.BAD_GATEWAY);
            }
            private ModelIntegrationException failure(IOException error) {
                return error instanceof java.io.InterruptedIOException
                        ? new ModelIntegrationException("MODEL_UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT) : failure();
            }
            private InputStream bounded(InputStream source) {
                return new FilterInputStream(source) {
                    private long bytes;
                    private void count(int size) throws IOException {
                        if (size > 0 && (bytes += size) > 16L * 1024 * 1024) throw new IOException("Model response exceeds size limit");
                    }
                    @Override public int read() throws IOException { int value = in.read(); if (value >= 0) count(1); return value; }
                    @Override public int read(byte[] buffer, int off, int len) throws IOException { int size = in.read(buffer, off, len); count(size); return size; }
                };
            }
        };
    }
}
