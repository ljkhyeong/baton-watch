package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.personal.baton.watch.adapter.out.external.check.ApacheUrlChecker;
import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 운영 조립과 실제 PostgreSQL에서 최상위 로거를 TRACE로 올려도 값이 남지 않는지 확인한다. 외부 호출만 대역이다. */
@SpringBootTest(
        classes = BatonWatchApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "logging.level.root=TRACE",
            // 시험 컨테이너를 조회하는 Docker 클라이언트 로그에는 운영에 없는 시험 DB 비밀번호가 나온다.
            "logging.level.com.github.dockerjava=OFF",
            "spring.datasource.password=service-connection-overridden",
            "watch.poll-interval=1s",
            "watch.maintenance-interval=1d",
            "watch.event-delivery.enabled=true",
            "watch.event-delivery.endpoint=https://callback.invalid/api/v1/internal/resource-health-events",
            "watch.event-delivery.poll-interval=1s"
        })
@Testcontainers
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class RuntimeTraceLoggingIntegrationTest {

    private static final String API_TOKEN = "trace-sensitive-api-token-0123456789abcdef";
    private static final String EVENT_TOKEN = "trace-sensitive-event-token-0123456789abcdef";
    private static final String DATABASE_PASSWORD = "trace-sensitive-database-password";
    private static final String REFERENCE = "trace-sensitive-reference";
    private static final String TARGET_PATH = "trace-sensitive-target";
    private static final String TARGET_QUERY = "trace-sensitive-query";

    @Container
    @ServiceConnection(name = "postgres")
    static final PostgreSQLContainer POSTGRES = BootstrapTestFixtures.postgres(DATABASE_PASSWORD);

    @DynamicPropertySource
    static void configureTokensOutsideTestContextLogs(DynamicPropertyRegistry registry) {
        registry.add("watch.api-token", () -> API_TOKEN);
        registry.add("watch.event-delivery.bearer-token", () -> EVENT_TOKEN);
    }

    @MockitoBean(enforceOverride = true)
    private ApacheUrlChecker urlChecker;

    @MockitoBean(enforceOverride = true)
    private ApacheHealthChangeEventSender eventSender;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void keepsCredentialsAndMonitoredValuesOutOfTraceLogsAcrossTheFullRuntime(CapturedOutput output) throws Exception {
        doAnswer(ignored -> CheckObservation.forHttpStatus(200, Duration.ZERO, 0)).when(urlChecker).check(any());
        doAnswer(ignored -> EventDeliveryObservation.forHttpStatus(204, null)).when(eventSender).send(any());
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + serverPort + "/api/v1/resource-monitors/" + REFERENCE))
                    .header("Authorization", "Bearer " + API_TOKEN)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("""
                            {"sourceRevision":1,"monitoringState":"ACTIVE",
                             "targetUrl":"https://example.com/%s?token=%s"}
                            """.formatted(TARGET_PATH, TARGET_QUERY)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM watch_health_change_event WHERE delivery_status = 'DELIVERED'",
                Integer.class)).isEqualTo(1));

        String logs = output.getAll();
        assertThat(List.of(API_TOKEN, EVENT_TOKEN, DATABASE_PASSWORD, REFERENCE, TARGET_PATH, TARGET_QUERY)
                .stream().filter(logs::contains).toList())
                .as("TRACE 로그에 자격 증명과 점검 대상 값을 남기지 않는다")
                .isEmpty();
    }
}
