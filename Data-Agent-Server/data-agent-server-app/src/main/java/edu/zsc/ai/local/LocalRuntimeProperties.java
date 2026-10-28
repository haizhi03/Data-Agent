package edu.zsc.ai.local;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.local")
public record LocalRuntimeProperties(Path dataDirectory, List<String> allowedOrigins) {
    public LocalRuntimeProperties {
        dataDirectory = dataDirectory == null
                ? Path.of(System.getProperty("user.home"), ".data-agent", "local") : dataDirectory;
        if (!dataDirectory.isAbsolute()) {
            throw new IllegalArgumentException("app.local.data-directory must be absolute");
        }
        dataDirectory = dataDirectory.normalize();
        allowedOrigins = allowedOrigins == null ? List.of("http://127.0.0.1:5174") : List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()) || "tauri".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || origin.contains("*") || uri.getPort() > 65535) {
                throw new IllegalArgumentException("app.local.allowed-origins requires exact origins");
            }
        }
    }
}
