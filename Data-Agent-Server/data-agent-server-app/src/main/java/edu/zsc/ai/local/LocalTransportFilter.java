package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.zsc.ai.context.AgentExecutionContext;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

final class LocalTransportFilter extends OncePerRequestFilter {
    private final LocalRuntimeIdentity identity;
    private final ObjectMapper mapper;
    private final CorsConfiguration cors = new CorsConfiguration();

    LocalTransportFilter(LocalRuntimeIdentity identity, LocalRuntimeProperties properties, ObjectMapper mapper) {
        this.identity = identity;
        this.mapper = mapper;
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "X-Local-Runtime-Id",
                "X-Workspace-Type", "X-Org-Id"));
        cors.setAllowCredentials(false);
    }

    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        clearContexts();
        response.setHeader("Cache-Control", "no-store");
        try {
            // Forwarded headers cannot expand access; only the socket peer counts.
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                throw LocalRequestException.forbidden();
            }
            String origin = request.getHeader("Origin");
            if (origin != null && (Collections.list(request.getHeaders("Origin")).size() != 1
                    || !cors.getAllowedOrigins().contains(origin))) {
                throw LocalRequestException.forbidden();
            }
            if (origin != null) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.addHeader("Vary", "Origin");
            }
            // Browser preflight carries no credential and never reaches an application handler.
            if (CorsUtils.isPreFlightRequest(request)) {
                var methods = cors.checkHttpMethod(org.springframework.http.HttpMethod.valueOf(
                        request.getHeader("Access-Control-Request-Method")));
                var requestedHeaders = request.getHeader("Access-Control-Request-Headers");
                var headers = cors.checkHeaders(requestedHeaders == null ? java.util.List.of()
                        : java.util.Arrays.stream(requestedHeaders.split(",")).map(String::trim).toList());
                if (methods == null || headers == null) throw LocalRequestException.forbidden();
                response.setHeader("Access-Control-Allow-Methods", String.join(", ", methods.stream()
                        .map(org.springframework.http.HttpMethod::name).toList()));
                if (!headers.isEmpty()) response.setHeader("Access-Control-Allow-Headers", String.join(", ", headers));
                response.setStatus(204);
                return;
            }
            if (origin == null && (Collections.list(request.getHeaders("Authorization")).size() != 1
                    || !identity.authenticates(request.getHeader("Authorization")))) {
                throw new LocalRequestException(401, "LOCAL_CLIENT_UNAUTHENTICATED", "本机通信凭证缺失或失效");
            }
            LocalScopeGuard.validateRequest(request);
            RequestContext.set(identity.newRequestContext());
            chain.doFilter(request, response);
        } catch (LocalRequestException e) {
            response.setStatus(e.status());
            response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getOutputStream(), e.response());
        } finally {
            clearContexts();
        }
    }

    private static void clearContexts() {
        AgentExecutionContext.clear();
        AgentRequestContext.clear();
        RequestContext.clear();
    }
}
