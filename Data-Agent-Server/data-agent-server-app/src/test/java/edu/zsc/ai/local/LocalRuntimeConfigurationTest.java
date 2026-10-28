package edu.zsc.ai.local;

import edu.zsc.ai.DataAgentApplication;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class LocalRuntimeConfigurationTest {
    @Test void defaultAndExplicitLocalModeNeverRegisterLegacyAccountOrDatabaseBeans() {
        for (var properties : List.of(java.util.Map.<String, Object>of(), java.util.Map.<String, Object>of("app.runtime.mode", "local"))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
                context.register(DataAgentApplication.class);
                context.addBeanFactoryPostProcessor(factory -> {
                    assertThat(factory.containsBeanDefinition("saTokenConfigure")).isFalse();
                    assertThat(factory.containsBeanDefinition("orgAccessServiceImpl")).isFalse();
                    assertThat(factory.containsBeanDefinition("requestContextAspect")).isFalse();
                    assertThat(factory.containsBeanDefinition("localRuntimeController")).isTrue();
                    assertThat(factory.containsBeanDefinition("localTransportFilter")).isTrue();
                    throw new StopBeforeLegacyInstantiation();
                });
                assertThatThrownBy(context::refresh).isInstanceOf(StopBeforeLegacyInstantiation.class);
            }
        }
        assertThat(new RuntimeModeProperties(null).mode()).isEqualTo(RuntimeModeProperties.Mode.LOCAL);
    }

    @ParameterizedTest @ValueSource(strings={"server","locla"})
    void invalidOrRetiredRuntimeModeFailsRatherThanSilentlyFallingBack(String mode) {
        new ApplicationContextRunner().withUserConfiguration(DataAgentApplication.class)
                .withPropertyValues("app.runtime.mode="+mode).run(context -> assertThat(context).hasFailed());
    }

    @Test void localDirectoryMustBeAbsoluteAndDoesNotCreateFiles() {
        assertThatThrownBy(() -> new LocalRuntimeProperties(Path.of("relative"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new LocalRuntimeProperties(null, null).dataDirectory()).isAbsolute();
    }

    @ParameterizedTest @ValueSource(strings = {"*", "null", "http://*.example.com", "https://example.com/path", "https://user@example.com", "https://example.com?x=1"})
    void originConfigurationRejectsWildcardsAndNonOrigins(String value) {
        assertThatThrownBy(() -> new LocalRuntimeProperties(null, List.of(value))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void publicBindConfigurationIsRejected() throws Exception {
        var properties = new ServerProperties(); properties.setAddress(InetAddress.getByName("0.0.0.0"));
        var factory = new TomcatServletWebServerFactory();
        assertThatThrownBy(() -> new LocalRuntimeConfiguration().localLoopbackBinding(properties).customize(factory))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("loopback");
    }

    @Test void forwardedPeerRewritingConfigurationIsRejected() {
        var properties = new ServerProperties();
        properties.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.FRAMEWORK);
        assertThatThrownBy(() -> new LocalRuntimeConfiguration().localLoopbackBinding(properties)
                .customize(new TomcatServletWebServerFactory())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forwarded headers");
    }

    @Test void nonLoopbackPeersAreRejectedEvenWithForwardedLoopbackHeader() throws Exception {
        var identity = new LocalRuntimeIdentity();
        var filter = new LocalTransportFilter(identity, new LocalRuntimeProperties(null, null), new com.fasterxml.jackson.databind.ObjectMapper());
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/runtime/capabilities");
        request.setRemoteAddr("192.0.2.1"); request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("Authorization", "Bearer " + identity.transportTokenForHost());
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Must not reach handler"); });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("LOCAL_SCOPE_FORBIDDEN");
        assertThat(edu.zsc.ai.context.RequestContext.hasContext()).isFalse();
    }

    private static class StopBeforeLegacyInstantiation extends RuntimeException { }
}
