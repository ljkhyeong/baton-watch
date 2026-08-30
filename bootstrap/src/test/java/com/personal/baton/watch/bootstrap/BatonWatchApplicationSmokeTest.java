package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.jdbc.JdbcTestUtils.countRowsInTable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = BatonWatchApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "management.endpoint.health.show-details=always",
            "management.endpoints.web.exposure.include=*",
            "spring.datasource.password=service-connection-overridden",
            "spring.task.scheduling.shutdown.await-termination=false",
            "watch.api-token=full-context-monitor-token-0123456789abcdef",
            "watch.poll-interval=1d",
            "watch.maintenance-interval=1d",
            "watch.event-delivery.enabled=true",
            "watch.event-delivery.endpoint=https://callback.invalid/api/v1/internal/resource-health-events",
            "watch.event-delivery.bearer-token=full-context-delivery-token-0123456789abcdef",
            "watch.event-delivery.poll-interval=1d",
            "watch.event-delivery.maintenance-interval=1d"
        })
@Testcontainers
@DirtiesContext
class BatonWatchApplicationSmokeTest {

    private static final String API_TOKEN = "full-context-monitor-token-0123456789abcdef";
    private static final String RESOURCE_REFERENCE = "root-context-smoke";
    private static final String MONITOR_PATH = "/api/v1/resource-monitors/" + RESOURCE_REFERENCE;

    @Container
    @ServiceConnection(name = "postgres")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
                    "postgres:18.6-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("baton_watch")
            .withUsername("baton_watch")
            .withPassword("integration-test");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final List<MeterRegistry> meterRegistries;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    BatonWatchApplicationSmokeTest(
            Environment environment,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            List<MeterRegistry> meterRegistries) {
        this.environment = environment;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.meterRegistries = meterRegistries;
    }

    @Test
    void startsTheProductionApplicationAndPersistsAnAuthenticatedInactiveMonitor() throws Exception {
        assertMigrationsAndRuntimePolicy();

        HttpResponse<String> status = get("/api/v1/system/status", null);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(status.body()).required("status").stringValue())
                .isEqualTo("UP");

        HttpResponse<String> unauthorized = put(MONITOR_PATH, null);
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(countRowsInTable(jdbc, "watch_monitor")).isZero();

        HttpResponse<String> synchronizedMonitor = put(MONITOR_PATH, API_TOKEN);
        assertThat(synchronizedMonitor.statusCode()).isEqualTo(200);
        assertMonitorResponse(synchronizedMonitor.body());

        HttpResponse<String> loadedMonitor = get(MONITOR_PATH, API_TOKEN);
        assertThat(loadedMonitor.statusCode()).isEqualTo(200);
        assertMonitorResponse(loadedMonitor.body());

        StoredMonitor stored = jdbc.queryForObject(
                """
                SELECT source_revision, monitor_status, current_health,
                       target_url, next_check_at
                FROM watch_monitor
                WHERE resource_reference = ?
                """,
                (resultSet, rowNumber) -> new StoredMonitor(
                        resultSet.getLong("source_revision"),
                        resultSet.getString("monitor_status"),
                        resultSet.getString("current_health"),
                        resultSet.getString("target_url"),
                        resultSet.getObject("next_check_at")),
                RESOURCE_REFERENCE);
        assertThat(stored).isEqualTo(new StoredMonitor(1, "INACTIVE", "UNKNOWN", null, null));
        assertThat(countRowsInTable(jdbc, "watch_attempt")).isZero();
        assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isZero();

        assertManagementEndpointsAndMetricsRegistries();
    }

    private void assertManagementEndpointsAndMetricsRegistries() throws Exception {
        assertThat(managementGet("/actuator/health").statusCode()).isEqualTo(200);
        HttpResponse<String> prometheus = managementGet("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_info", "process_uptime_seconds");
        // 빈 배치도 완료 횟수를 남겨 미실행과 유휴 상태를 구분할 수 있어야 한다.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String scrape = managementGet("/actuator/prometheus").body();
            for (String method : List.of(
                    "checkDueMonitors", "deliverPendingEvents", "markStaleProjections",
                    "purgeAttemptHistory", "updateDatabaseClockOffset",
                    "purgeDeliveredEventHistory", "refreshEventDeliveryBacklog")) {
                assertThat(scrape.lines().filter(line ->
                        line.startsWith("tasks_scheduled_execution_seconds_count{")
                                && line.contains("code_function=\"" + method + "\"")
                                && line.contains("outcome=\"SUCCESS\"")))
                        .as("예약 작업 완료 지표: %s", method)
                        .anyMatch(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) > 0);
            }
        });
        assertThat(managementGet("/actuator/scheduledtasks").statusCode()).isEqualTo(404);

        assertThat(meterRegistries).anyMatch(PrometheusMeterRegistry.class::isInstance);
    }

    private HttpResponse<String> managementGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + managementPort + path))
                .timeout(Duration.ofSeconds(5))
                .header(HttpHeaders.ACCEPT, "*/*")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertMigrationsAndRuntimePolicy() {
        List<String> appliedVersions = jdbc.queryForList(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank
                """,
                String.class);
        assertThat(appliedVersions).containsSubsequence("1", "2", "3", "4");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
        assertThat(environment.getProperty("spring.task.scheduling.shutdown.await-termination"))
                .isEqualTo("true");
        assertThat(environment.getProperty("spring.task.scheduling.shutdown.await-termination-period"))
                .isEqualTo("65s");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET(), token);
    }

    private HttpResponse<String> put(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder(uri(path))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"sourceRevision\":1,\"monitoringState\":\"INACTIVE\"}")),
                token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
        request.timeout(Duration.ofSeconds(5));
        request.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }

    private void assertMonitorResponse(String responseBody) throws Exception {
        JsonNode response = objectMapper.readTree(responseBody);
        assertThat(response.required("resourceReference").stringValue())
                .isEqualTo(RESOURCE_REFERENCE);
        assertThat(response.required("sourceRevision").longValue()).isEqualTo(1);
        assertThat(response.required("monitoringState").stringValue()).isEqualTo("INACTIVE");
        assertThat(response.required("health").stringValue()).isEqualTo("UNKNOWN");
    }

    private record StoredMonitor(
            long sourceRevision,
            String monitorStatus,
            String currentHealth,
            String targetUrl,
            Object nextCheckAt) {
    }
}
