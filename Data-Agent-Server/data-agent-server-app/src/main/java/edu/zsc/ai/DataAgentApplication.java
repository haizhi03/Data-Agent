package edu.zsc.ai;

import edu.zsc.ai.local.LocalRuntimeConfiguration;
import edu.zsc.ai.local.RuntimeModeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Local-only application. Legacy account, ORM and chat graphs are never scanned. */
@SpringBootConfiguration
@EnableConfigurationProperties(RuntimeModeProperties.class)
@Import(LocalRuntimeConfiguration.class)
public class DataAgentApplication {
    public static void main(String[] args) { SpringApplication.run(DataAgentApplication.class, args); }
}
