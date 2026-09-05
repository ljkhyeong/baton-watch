package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.personal.baton.watch.adapter.out.external.check.ApacheUrlChecker;
import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 Spring Boot HTTP·Hikari·PostgreSQL·예약 작업을 함께 실행하는 부하·복구 시험이다.
 * 외부 네트워크만 결정적인 테스트 더블로 교체한다.
 */
@SpringBootTest(
        classes = BatonWatchApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "spring.datasource.password=service-connection-overridden",
            "spring.task.scheduling.shutdown.await-termination=false",
            "watch.api-token=runtime-load-monitor-token-0123456789abcdef",
            "watch.database.maximum-pool-size=2",
            "watch.database.minimum-idle=0",
            "watch.database.connection-timeout-millis=500",
            "watch.database.validation-timeout-millis=250",
            "watch.database.idle-timeout-millis=10000",
            "watch.database.max-lifetime-millis=60000",
            "watch.database.keepalive-time-millis=30000",
            "watch.persistence.query-timeout=1s",
            "watch.persistence.transaction-timeout=2s",
            "watch.persistence.lock-timeout=100ms",
            "watch.poll-interval=1s",
            "watch.maintenance-interval=1m",
            "watch.worker-execution-budget=60s",
            "watch.lease-duration=10s",
            "watch.check-batch-size=10",
            "watch.http.connect-timeout=100ms",
            "watch.http.response-timeout=100ms",
            "watch.http.total-timeout=250ms",
            "watch.event-delivery.enabled=true",
            "watch.event-delivery.endpoint=https://callback.invalid/api/v1/internal/resource-health-events",
            "watch.event-delivery.bearer-token=runtime-load-delivery-token-0123456789abcdef",
            "watch.event-delivery.poll-interval=1s",
            "watch.event-delivery.maintenance-interval=1m",
            "watch.event-delivery.lease-duration=10s",
            "watch.event-delivery.initial-retry-delay=5s",
            "watch.event-delivery.max-retry-delay=5s",
            "watch.event-delivery.batch-size=10",
            "watch.event-delivery.http.connect-timeout=100ms",
            "watch.event-delivery.http.response-timeout=100ms",
            "watch.event-delivery.http.total-timeout=250ms"
        })
@Testcontainers
@DirtiesContext
@Tag("runtime-load")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class BatonWatchRuntimeLoadTest {

    private static final String API_TOKEN = "runtime-load-monitor-token-0123456789abcdef";
    private static final Duration CHECK_DELAY = Duration.ofMillis(100);

    @Container
    @ServiceConnection(name = "postgres")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
                    "postgres:18.6-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("baton_watch")
            .withUsername("baton_watch")
            .withPassword("runtime-load-test");

    @MockitoBean(enforceOverride = true)
    private ApacheUrlChecker urlChecker;

    @MockitoBean(enforceOverride = true)
    private ApacheHealthChangeEventSender eventSender;

    private final AtomicBoolean deliveryAvailable = new AtomicBoolean();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MonitoringMaintenanceScheduler monitoringMaintenance;
    private final EventDeliveryMaintenanceScheduler deliveryMaintenance;
    private CountDownLatch checksMayComplete;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    BatonWatchRuntimeLoadTest(
            HikariDataSource dataSource,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MonitoringMaintenanceScheduler monitoringMaintenance,
            EventDeliveryMaintenanceScheduler deliveryMaintenance) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.monitoringMaintenance = monitoringMaintenance;
        this.deliveryMaintenance = deliveryMaintenance;
    }

    @BeforeEach
    void configureExternalTestDoubles() {
        checksMayComplete = new CountDownLatch(1);
        deliveryAvailable.set(false);
        doAnswer(ignored -> {
                    try {
                        if (!checksMayComplete.await(30, TimeUnit.SECONDS)) {
                            return CheckObservation.internalFailure();
                        }
                        Thread.sleep(CHECK_DELAY);
                        return CheckObservation.forHttpStatus(200, CHECK_DELAY, 0, 0);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return CheckObservation.internalFailure();
                    }
                })
                .when(urlChecker)
                .check(any());
        doAnswer(ignored -> EventDeliveryObservation.forHttpStatus(
                        deliveryAvailable.get() ? 204 : 503))
                .when(eventSender)
                .send(any());
    }

    @Test
    void recoversTheFullRuntimeFromPoolAndDeliveryFailures() throws Exception {
        int monitors = Integer.parseInt(System.getProperty("watch.runtime-load.monitors", "25"));
        assertThat(monitors).as("시험 모니터 수: 1~50").isBetween(1, 50);
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(2);

        long started = System.nanoTime();
        assertPoolSaturationAndRecovery();
        long poolRecoveredAt = System.nanoTime();

        List<CompletableFuture<HttpResponse<String>>> synchronizations = IntStream.range(0, monitors)
                .mapToObj(this::synchronizeActiveMonitor)
                .toList();
        CompletableFuture.allOf(synchronizations.toArray(CompletableFuture[]::new))
                .get(30, TimeUnit.SECONDS);
        assertThat(synchronizations)
                .allSatisfy(future -> assertThat(future.join().statusCode()).isEqualTo(200));
        assertThat(monitorCount()).isEqualTo(monitors);
        long synchronizedAt = System.nanoTime();

        jdbc.update("""
                UPDATE watch_monitor
                SET next_check_at = transaction_timestamp() - INTERVAL '2 minutes'
                WHERE resource_reference LIKE 'runtime-load-%'
                """);
        monitoringMaintenance.refreshCheckScheduleDelay();
        assertThat(metric(managementScrape(), "baton_watch_check_schedule_delay_seconds"))
                .isGreaterThanOrEqualTo(119);

        checksMayComplete.countDown();
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(completedMonitorCount()).isEqualTo(monitors);
            assertThat(unfinishedMonitorCount()).isZero();
            assertThat(eventCount()).isEqualTo(monitors);
        });
        long checksCompletedAt = System.nanoTime();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(failedDeliveryCount()).isPositive());
        deliveryMaintenance.refreshEventDeliveryBacklog();
        assertThat(metric(managementScrape(), "baton_watch_event_delivery_backlog"))
                .isEqualTo(monitors);

        deliveryAvailable.set(true);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(eventStatusCount("DELIVERED")).isEqualTo(monitors);
            assertThat(eventStatusCount("PENDING")).isZero();
        });
        long deliveriesCompletedAt = System.nanoTime();

        monitoringMaintenance.refreshCheckScheduleDelay();
        deliveryMaintenance.refreshEventDeliveryBacklog();
        String scrape = managementScrape();
        assertThat(metric(scrape, "baton_watch_check_schedule_delay_seconds")).isZero();
        assertThat(metric(scrape, "baton_watch_event_delivery_backlog")).isZero();
        assertThat(metric(
                        scrape,
                        "baton_watch_check_attempts_total",
                        "outcome=\"success\""))
                .isGreaterThanOrEqualTo(monitors);
        assertThat(metric(
                        scrape,
                        "baton_watch_event_delivery_attempts_total",
                        "outcome=\"http_server_error\""))
                .isPositive();
        assertThat(metric(
                        scrape,
                        "baton_watch_event_delivery_attempts_total",
                        "outcome=\"delivered\""))
                .isGreaterThanOrEqualTo(monitors);
        assertThat(metric(
                        scrape,
                        "tasks_scheduled_execution_seconds_count",
                        "code_function=\"checkDueMonitors\"",
                        "outcome=\"SUCCESS\""))
                .isPositive();
        assertThat(metric(
                        scrape,
                        "tasks_scheduled_execution_seconds_count",
                        "code_function=\"deliverPendingEvents\"",
                        "outcome=\"SUCCESS\""))
                .isPositive();
        assertThat(metric(scrape, "hikaricp_connections_max")).isEqualTo(2);

        System.out.printf(
                "전체 런타임 부하·복구 결과: 모니터=%d 풀복구=%dms 동기화=%dms "
                        + "점검완료=%dms 전달복구=%dms 최종백로그=0%n",
                monitors,
                elapsedMillis(started, poolRecoveredAt),
                elapsedMillis(poolRecoveredAt, synchronizedAt),
                elapsedMillis(synchronizedAt, checksCompletedAt),
                elapsedMillis(checksCompletedAt, deliveriesCompletedAt));
    }

    private void assertPoolSaturationAndRecovery() throws Exception {
        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection()) {
            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
            HttpResponse<String> unavailable = synchronizeInactiveMonitor("pool-saturation");
            assertThat(unavailable.statusCode()).isEqualTo(500);
            JsonNode body = objectMapper.readTree(unavailable.body());
            assertThat(body.required("code").stringValue()).isEqualTo("INTERNAL_ERROR");
            assertThat(unavailable.body())
                    .doesNotContain(
                            POSTGRES.getJdbcUrl(),
                            POSTGRES.getPassword(),
                            "Connection is not available");
        }

        HttpResponse<String> recovered = synchronizeInactiveMonitor("pool-saturation");
        assertThat(recovered.statusCode()).isEqualTo(200);
    }

    private CompletableFuture<HttpResponse<String>> synchronizeActiveMonitor(int index) {
        String body = """
                {"sourceRevision":1,"monitoringState":"ACTIVE",
                 "targetUrl":"https://runtime-load.example/check/%d"}
                """.formatted(index);
        return httpClient.sendAsync(
                monitorRequest("runtime-load-%03d".formatted(index), body),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> synchronizeInactiveMonitor(String resourceReference)
            throws Exception {
        return httpClient.send(
                monitorRequest(
                        resourceReference,
                        """
                        {"sourceRevision":1,"monitoringState":"INACTIVE"}
                        """),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest monitorRequest(String resourceReference, String body) {
        return HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + serverPort
                                + "/api/v1/resource-monitors/" + resourceReference))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_TOKEN)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private int monitorCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM watch_monitor WHERE resource_reference LIKE 'runtime-load-%'",
                Integer.class);
    }

    private int completedMonitorCount() {
        return jdbc.queryForObject(
                """
                SELECT count(DISTINCT attempt.resource_reference)
                FROM watch_attempt attempt
                JOIN watch_result result USING (attempt_id)
                WHERE attempt.resource_reference LIKE 'runtime-load-%'
                """,
                Integer.class);
    }

    private int unfinishedMonitorCount() {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM watch_monitor
                WHERE resource_reference LIKE 'runtime-load-%'
                  AND (lease_token IS NOT NULL
                       OR next_check_at <= transaction_timestamp())
                """,
                Integer.class);
    }

    private int eventCount() {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM watch_health_change_event
                WHERE resource_reference LIKE 'runtime-load-%'
                """,
                Integer.class);
    }

    private int failedDeliveryCount() {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM watch_health_change_event
                WHERE resource_reference LIKE 'runtime-load-%'
                  AND delivery_attempt > 0
                  AND last_delivery_outcome = 'HTTP_SERVER_ERROR'
                """,
                Integer.class);
    }

    private int eventStatusCount(String status) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM watch_health_change_event
                WHERE resource_reference LIKE 'runtime-load-%'
                  AND delivery_status = ?
                """,
                Integer.class,
                status);
    }

    private String managementScrape() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static double metric(String scrape, String name, String... labels) {
        return scrape.lines()
                .filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
                .filter(line -> Arrays.stream(labels).allMatch(line::contains))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("메트릭을 찾을 수 없습니다: " + name));
    }

    private static long elapsedMillis(long started, long completed) {
        return TimeUnit.NANOSECONDS.toMillis(completed - started);
    }
}
