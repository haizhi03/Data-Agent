package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import edu.zsc.ai.config.db.ConnectionPoolProperties;
import edu.zsc.ai.domain.service.db.impl.HikariManagedDataSourceFactory;
import org.springframework.context.ApplicationEventPublisher;

/** Local composition root. Import migrated modules here; never scan the legacy account/database graph. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.runtime.mode", havingValue = "local", matchIfMissing = true)
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class, excludeName = {
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        "cn.dev33.satoken.spring.SaTokenContextRegister",
        "cn.dev33.satoken.spring.SaBeanRegister",
        "cn.dev33.satoken.spring.SaBeanInject",
        "cn.dev33.satoken.spring.sso.SaSsoBeanRegister",
        "cn.dev33.satoken.spring.sso.SaSsoBeanInject",
        "cn.dev33.satoken.spring.oauth2.SaOAuth2BeanRegister",
        "cn.dev33.satoken.spring.oauth2.SaOAuth2BeanInject",
        "cn.dev33.satoken.spring.apikey.SaApiKeyBeanRegister",
        "cn.dev33.satoken.spring.apikey.SaApiKeyBeanInject",
        "cn.dev33.satoken.spring.sign.SaSignBeanRegister",
        "cn.dev33.satoken.spring.sign.SaSignBeanInject"
})
@EnableConfigurationProperties({LocalRuntimeProperties.class, ConnectionPoolProperties.class})
@EnableAsync
@org.springframework.context.annotation.Import({LocalWorkbenchConfiguration.class, LocalAssistantConfiguration.class})
public class LocalRuntimeConfiguration {
    @Bean LocalRuntimeIdentity localRuntimeIdentity() { return new LocalRuntimeIdentity(); }
    @Bean LocalRuntimeController localRuntimeController(LocalRuntimeIdentity identity, LocalConnectionService connections,
            LocalAssistantService assistant) { return new LocalRuntimeController(identity, connections, assistant); }
    @Bean LocalRequestAdvice localRequestAdvice(ObjectMapper mapper) { return new LocalRequestAdvice(mapper); }
    @Bean LocalConnectionRepository localConnectionRepository(LocalRuntimeProperties properties) {
        return new LocalConnectionRepository(properties.dataDirectory());
    }
    @Bean LocalConnectionService localConnectionService(LocalConnectionRepository repository,
            ConnectionPoolProperties pool, ApplicationEventPublisher events) {
        var localPool = new ConnectionPoolProperties();
        org.springframework.beans.BeanUtils.copyProperties(pool, localPool);
        localPool.setReadOnly(true);
        return new LocalConnectionService(repository, new HikariManagedDataSourceFactory(localPool), events);
    }
    @Bean LocalConnectionController localConnectionController(LocalConnectionService service) { return new LocalConnectionController(service); }

    @Bean
    FilterRegistrationBean<LocalTransportFilter> localTransportFilter(LocalRuntimeIdentity identity,
            LocalRuntimeProperties properties, ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new LocalTransportFilter(identity, properties, mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> localLoopbackBinding(ServerProperties server) {
        return factory -> {
            try {
                if (server.getForwardHeadersStrategy() != null
                        && server.getForwardHeadersStrategy() != ServerProperties.ForwardHeadersStrategy.NONE) {
                    throw new IllegalArgumentException("Local runtime cannot trust forwarded headers");
                }
                InetAddress address = server.getAddress() == null
                        ? InetAddress.getByAddress(new byte[]{127, 0, 0, 1}) : server.getAddress();
                if (!address.isLoopbackAddress()) throw new IllegalArgumentException("Local runtime must bind to loopback");
                factory.setAddress(address);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Bean(name = {"taskExecutor", "localExecutor"})
    ThreadPoolTaskExecutor localExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("local-");
        executor.setTaskDecorator(new LocalContextTaskDecorator());
        return executor;
    }

    @Bean
    WebMvcConfigurer localAsyncSupport(ThreadPoolTaskExecutor localExecutor) {
        return new WebMvcConfigurer() {
            @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(localExecutor);
                configurer.setDefaultTimeout(180000);
            }
        };
    }
}
