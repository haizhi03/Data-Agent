package edu.zsc.ai.local;

/** Test-classpath-only host. Not included in the application JAR or exposed by any production configuration. */
public final class LocalAssistantBrowserHost {
    public static void main(String[] args) {
        new org.springframework.boot.builder.SpringApplicationBuilder(edu.zsc.ai.DataAgentApplication.class,LocalAssistantHttpTest.Fixture.class).run(args);
    }
}
