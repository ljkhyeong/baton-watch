package com.personal.baton.watch.bootstrap;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** 외부 설정이 완화할 수 없는 인바운드·종료·관리·로깅 안전값을 고정한다. */
public final class RuntimeSafetyEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "watchRuntimeSafety";

    private static final Map<String, Object> FIXED_PROPERTIES = Map.ofEntries(
            Map.entry("spring.lifecycle.timeout-per-shutdown-phase", "30s"),
            Map.entry("spring.task.scheduling.shutdown.await-termination", "true"),
            Map.entry("spring.task.scheduling.shutdown.await-termination-period", "65s"),
            Map.entry("server.max-http-request-header-size", "8KB"),
            Map.entry("server.tomcat.max-http-response-header-size", "8KB"),
            Map.entry("server.tomcat.max-connections", "128"),
            Map.entry("server.tomcat.accept-count", "32"),
            Map.entry("server.tomcat.threads.max", "32"),
            Map.entry("server.tomcat.threads.min-spare", "4"),
            Map.entry("server.tomcat.threads.max-queue-capacity", "64"),
            // 폼 필터는 인증 전에 본문 전체를 읽고, multipart 해석은 핸들러 선택 전에 본문을 읽는다.
            Map.entry("spring.mvc.formcontent.filter.enabled", "false"),
            Map.entry("spring.servlet.multipart.enabled", "false"),
            Map.entry("logging.level.org.apache.hc.client5.http.headers", "OFF"),
            Map.entry("logging.level.org.apache.hc.client5.http.wire", "OFF"),
            Map.entry("logging.level.org.apache.hc.client5.http.impl", "OFF"),
            Map.entry("logging.level.org.apache.hc.client5.http.ssl", "OFF"),
            Map.entry("logging.level.org.apache.coyote.http11.Http11Processor", "OFF"),
            Map.entry("logging.level.org.apache.coyote.http11.Http11InputBuffer", "OFF"),
            Map.entry("logging.level.org.apache.catalina.authenticator.AuthenticatorBase", "OFF"),
            Map.entry("logging.level.org.apache.tomcat.util.http.Parameters", "OFF"),
            Map.entry("logging.level.org.apache.tomcat.util.http.Rfc6265CookieProcessor", "OFF"),
            Map.entry("logging.level.org.apache.tomcat.util.http.parser.Cookie", "OFF"),
            Map.entry("logging.level.org.postgresql.core.v3.QueryExecutorImpl", "OFF"),
            Map.entry("logging.level.org.springframework.jdbc.core.StatementCreatorUtils", "OFF"),
            Map.entry("logging.level.org.springframework.web.servlet.DispatcherServlet", "OFF"),
            Map.entry("logging.level.org.springframework.web.method.HandlerMethod", "OFF"),
            Map.entry(
                    "logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor", "OFF"),
            Map.entry(
                    "logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor", "OFF"),
            Map.entry(
                    "logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver", "OFF"),
            Map.entry("logging.level.org.springframework.web.servlet.resource.ResourceHandlerUtils", "OFF"),
            Map.entry("logging.level.org.springframework.security.web.FilterChainProxy", "OFF"),
            Map.entry(
                    "logging.level.org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager", "OFF"),
            Map.entry("management.server.address", "127.0.0.1"),
            Map.entry("management.endpoint.health.show-details", "never"),
            Map.entry("management.endpoints.web.exposure.include", "health,prometheus"));

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, FIXED_PROPERTIES));
    }
}
