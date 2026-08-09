package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.jdbc.JdbcTestUtils.countRowsInTable;

import com.personal.baton.watch.adapter.out.external.check.ApacheUrlChecker;
import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcCheckWorkPersistenceAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcHealthChangeEventDeliveryAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcMonitorPersistenceAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.PostgresTransactionOperations;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = BatonWatchApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=-1",
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
@Testcontainers(disabledWithoutDocker = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BatonWatchApplicationSmokeTest {

    private static final String API_TOKEN = "full-context-monitor-token-0123456789abcdef";
    private static final String RESOURCE_REFERENCE = "root-context-smoke";
    private static final String MONITOR_PATH = "/api/v1/resource-monitors/" + RESOURCE_REFERENCE;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("baton_watch")
            .withUsername("baton_watch")
            .withPassword("integration-test");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ApplicationContext applicationContext;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @LocalServerPort
    private int serverPort;

    @Autowired
    BatonWatchApplicationSmokeTest(
            ApplicationContext applicationContext,
            DataSource dataSource,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.applicationContext = applicationContext;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Test
    void startsTheProductionApplicationAndPersistsAnAuthenticatedInactiveMonitor() throws Exception {
        assertDatabaseAndMigrations();
        assertProductionAssembly();

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
    }

    private void assertDatabaseAndMigrations() throws Exception {
        List<String> appliedVersions = jdbc.queryForList(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank
                """,
                String.class);
        assertThat(appliedVersions).containsExactly("1", "2");
        Integer tableCount = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'watch_monitor',
                      'watch_attempt',
                      'watch_result',
                      'watch_health_change_event'
                  )
                """,
                Integer.class);
        assertThat(tableCount).isEqualTo(4);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("baton_watch");
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getMetaData().getUserName()).isEqualToIgnoringCase("baton_watch");
        }
    }

    private void assertProductionAssembly() {
        assertThat(applicationContext.getBean(BatonWatchApplication.class)).isNotNull();
        assertThat(applicationContext.getBean(JdbcMonitorPersistenceAdapter.class)).isNotNull();
        assertThat(applicationContext.getBean(JdbcCheckWorkPersistenceAdapter.class)).isNotNull();
        assertThat(applicationContext.getBean(JdbcHealthChangeEventDeliveryAdapter.class)).isNotNull();
        assertThat(applicationContext.getBean(PostgresTransactionOperations.class)).isNotNull();
        assertThat(applicationContext.getBean(ApacheUrlChecker.class)).isNotNull();
        assertThat(applicationContext.getBean(ApacheHealthChangeEventSender.class)).isNotNull();
        assertThat(applicationContext.getBean(RunEventDeliveriesUseCase.class)).isNotNull();
        assertThat(applicationContext.getBean(MonitoringScheduler.class)).isNotNull();
        assertThat(applicationContext.getBean(EventDeliveryScheduler.class)).isNotNull();
        assertThat(applicationContext.getBean(EventDeliveryMaintenanceScheduler.class)).isNotNull();
        assertThat(applicationContext.getBean(RedactingScheduledTaskErrorHandler.class)).isNotNull();
        assertThat(applicationContext.getEnvironment()
                        .getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");

        Map<String, ThreadPoolTaskScheduler> schedulers =
                applicationContext.getBeansOfType(ThreadPoolTaskScheduler.class);
        assertThat(schedulers).containsOnlyKeys(
                WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER,
                WorkerSchedulingConfiguration.EVENT_DELIVERY_TASK_SCHEDULER,
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER);
        schedulers.values().forEach(scheduler ->
                assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1));
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
