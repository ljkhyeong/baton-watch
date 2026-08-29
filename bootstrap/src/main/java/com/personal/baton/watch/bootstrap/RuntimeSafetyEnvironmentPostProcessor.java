package com.personal.baton.watch.bootstrap;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** 외부 설정이 완화할 수 없는 인바운드·종료·관리·로깅 안전값을 고정한다. */
public final class RuntimeSafetyEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "watchRuntimeSafety";

    private static final Map<String, Object> FIXED_PROPERTIES = Map.ofEntries(
            Map.entry("spring.task.scheduling.shutdown.await-termination", "true"),
            Map.entry("spring.task.scheduling.shutdown.await-termination-period", "65s"),
            Map.entry("server.max-http-request-header-size", "8KB"),
            Map.entry("server.tomcat.max-http-response-header-size", "8KB"),
            Map.entry("server.tomcat.max-connections", "128"),
            Map.entry("server.tomcat.accept-count", "32"),
            Map.entry("server.tomcat.threads.max", "32"),
            Map.entry("server.tomcat.threads.min-spare", "4"),
            Map.entry("server.tomcat.threads.max-queue-capacity", "64"),
            Map.entry("logging.level.org.apache.hc.client5.http.headers", "OFF"),
            Map.entry("logging.level.org.apache.hc.client5.http.wire", "OFF"),
            Map.entry("logging.level.org.apache.hc.client5.http.impl", "OFF"),
            Map.entry("logging.level.org.apache.hc.client5.http.ssl", "OFF"),
            Map.entry("management.endpoint.health.show-details", "never"),
            Map.entry("management.endpoints.web.exposure.include", "health,prometheus"));

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, FIXED_PROPERTIES));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
