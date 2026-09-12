package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = MonitorApiSecurityIntegrationTest.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=-1",
            "management.endpoint.health.group.readiness.include=readinessState",
            "spring.mvc.log-request-details=true",
            "logging.level.org.springframework.web=TRACE",
            "logging.level.org.springframework.web.servlet.DispatcherServlet=TRACE",
            "logging.level.org.springframework.web.method.HandlerMethod=TRACE",
            "logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor=TRACE",
            "logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver=TRACE",
            "logging.level.org.springframework.security.web=TRACE",
            "logging.level.org.springframework.security.web.FilterChainProxy=TRACE",
            "logging.level.org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager=TRACE"
        })
@ExtendWith(OutputCaptureExtension.class)
class InboundLoggingIntegrationTest {

    private static final String API_TOKEN = "monitor-api-token-0123456789-abcdef";
    private static final String REFERENCE = "log-sensitive-resource";
    private static final String QUERY = "log-sensitive-query";
    private static final String TARGET = "https://example.com/log-sensitive-target?token=private-value";

    @LocalServerPort
    private int serverPort;

    @Test
    void keepsRequestAndResponseValuesOutOfDetailedFrameworkLogs(CapturedOutput output) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI monitor = uri("/api/v1/resource-monitors/" + REFERENCE + "?trace=" + QUERY);
            var unauthorized = client.send(HttpRequest.newBuilder(monitor).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);

            var synchronizedMonitor = client.send(HttpRequest.newBuilder(monitor)
                    .header("Authorization", "Bearer " + API_TOKEN)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("""
                            {"sourceRevision":42,"monitoringState":"ACTIVE","targetUrl":"%s"}
                            """.formatted(TARGET)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(synchronizedMonitor.statusCode()).isEqualTo(200);
            assertThat(synchronizedMonitor.body()).contains("resource-1");

            var unavailable = client.send(HttpRequest.newBuilder(uri("/api/v1/resource-monitors/storage-unavailable"))
                    .header("Authorization", "Bearer " + API_TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unavailable.statusCode()).isEqualTo(503);
        }

        String logs = output.getAll();
        assertThat(List.of(REFERENCE, QUERY, TARGET, API_TOKEN, "resource-1", "raw-storage-secret", "raw-sql-secret")
                .stream().filter(logs::contains).toList())
                .as("상세 로그에 요청·응답 원문과 예외 원문을 남기지 않는다")
                .isEmpty();
        assertThat(logs).contains("모니터 API 처리 실패 failureType=CannotGetJdbcConnectionException");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }
}
