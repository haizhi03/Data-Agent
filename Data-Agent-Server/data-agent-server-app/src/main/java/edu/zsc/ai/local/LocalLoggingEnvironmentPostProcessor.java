package edu.zsc.ai.local;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;

/** Applies before logging initialization, including default startup without an explicit runtime mode. */
public final class LocalLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if ("local".equals(environment.getProperty("app.runtime.mode", "local"))) {
            environment.getPropertySources().addFirst(new MapPropertySource("localContentPrivacy",
                    Map.of("logging.config","classpath:logback-local.xml")));
        }
    }
}
