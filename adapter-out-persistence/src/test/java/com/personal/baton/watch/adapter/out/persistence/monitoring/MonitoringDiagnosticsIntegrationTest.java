package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class MonitoringDiagnosticsIntegrationTest extends MonitoringPersistenceIntegrationTestSupport {

    private static final String REFERENCE = "diagnostics-fixture";
    private static final String TARGET = "https://private.example/check?token=private-fixture";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temporary;

    @ParameterizedTest(name = "조회 제한 {0}: 최신 이력 {1}건과 읽기 전용·정보 제외 확인")
    @CsvSource({"default,50", "2,2", "100,100"})
    void readsBoundedHistoriesWithoutChangingDataOrExposingTargets(String limit, int expected) throws Exception {
        synchronize(REFERENCE, 1, TARGET, BASE_TIME);
        var check = claimOne();
        checkWorkPersistence.finalizeCheck(finalization(check,
                CheckObservation.forHttpStatus(503, Duration.ofMillis(125), 16, 0),
                check.claimedAt(), check.claimedAt().plus(INTERVAL)));
        var deliveries = new JdbcHealthChangeEventDeliveryAdapter(
                JdbcClient.create(jdbc), newTransactionOperations());
        var event = deliveries.claimPendingEvent(LEASE).orElseThrow();
        deliveries.finalizeDelivery(new EventDeliveryFinalization(
                event.payload().eventId(), event.leaseToken(),
                EventDeliveryObservation.forHttpStatus(503), event.claimedAt(),
                event.claimedAt().plusSeconds(5)));
        insertOlderHistory();
        var before = databaseRows();

        var result = limit.equals("default")
                ? runTool(POSTGRES.getContainerId(), REFERENCE)
                : runTool(POSTGRES.getContainerId(), REFERENCE, limit);
        assertThat(result.status()).as(result.output()).isZero();
        var report = JSON.readTree(result.output());
        assertThat(report.propertyNames()).containsExactlyInAnyOrder(
                "observedAt", "readOnly", "monitor", "checks", "deliveries");
        assertThat(report.get("readOnly").asBoolean()).isTrue();
        assertThat(report.at("/monitor/health").asString()).isEqualTo("DEGRADED");
        assertThat(report.get("checks").size()).isEqualTo(expected);
        assertThat(report.get("deliveries").size()).isEqualTo(expected);
        assertThat(report.at("/checks/0/attemptId").asString()).isEqualTo(check.attemptId().toString());
        assertThat(report.at("/checks/0/outcome").asString()).isEqualTo("HTTP_SERVER_ERROR");
        assertThat(report.at("/checks/0/httpStatusCode").asInt()).isEqualTo(503);
        assertThat(report.at("/checks/0/durationSeconds").asDouble()).isEqualTo(0.125);
        assertThat(report.at("/checks/1/outcome").isNull()).isTrue();
        assertThat(report.at("/deliveries/0/eventId").asString()).isEqualTo(event.payload().eventId().toString());
        assertThat(report.at("/deliveries/0/deliveryStatus").asString()).isEqualTo("PENDING");
        assertThat(report.at("/deliveries/0/deliveryAttempt").asInt()).isOne();
        assertThat(report.at("/deliveries/0/lastDeliveryOutcome").asString()).isEqualTo("HTTP_SERVER_ERROR");
        assertThat(report.at("/deliveries/0/lastHttpStatusCode").asInt()).isEqualTo(503);
        assertThat(report.at("/deliveries/0/nextAttemptAt").isString()).isTrue();
        assertThat(report.at("/deliveries/1/deliveryStatus").asString()).isEqualTo("DELIVERED");
        assertThat(result.output()).doesNotContain(
                TARGET, REFERENCE, "private.example", "private-fixture", "targetUrl", "leaseToken",
                check.leaseToken().toString(), event.leaseToken().toString());
        assertThat(databaseRows()).isEqualTo(before);

        var missing = runTool(POSTGRES.getContainerId(), "missing-resource");
        assertThat(missing.status()).as(missing.output()).isZero();
        var empty = JSON.readTree(missing.output());
        assertThat(empty.get("monitor").isNull()).isTrue();
        assertThat(empty.get("checks").size()).isZero();
        assertThat(empty.get("deliveries").size()).isZero();
    }

    @ParameterizedTest(name = "허용 범위 밖 조회 건수 {0} 거부")
    @ValueSource(strings = {"0", "101", "1;select 1"})
    void rejectsInvalidLimit(String limit) throws Exception {
        var result = runTool(POSTGRES.getContainerId(), REFERENCE, limit);
        assertThat(result.status()).isNotZero();
        assertThat(result.output()).contains("조회 건수는 1부터 100까지의 정수");
    }

    @Test
    void rejectsInvalidReferenceAndReportsConnectionFailuresWithoutRawErrors() throws Exception {
        var invalid = runTool(POSTGRES.getContainerId(), TARGET);
        assertThat(invalid.status()).isNotZero();
        assertThat(invalid.output()).contains("리소스 참조 형식").doesNotContain(TARGET);
        var missing = runTool("watch-missing-container-" + System.nanoTime(), REFERENCE);
        assertThat(missing.status()).isNotZero();
        assertThat(missing.output()).contains("진단 조회 실패").doesNotContain(REFERENCE, "Error response");
    }

    @Test
    void boundsLockWaitAndDoesNotPrintPartialReport() throws Exception {
        try (var connection = testDataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("LOCK TABLE watch_attempt IN ACCESS EXCLUSIVE MODE");
            try {
                var result = runTool(POSTGRES.getContainerId(), REFERENCE);
                assertThat(result.status()).isNotZero();
                assertThat(result.output()).contains("진단 조회 실패").doesNotContain("observedAt", REFERENCE);
            } finally {
                connection.rollback();
            }
        }
    }

    private void insertOlderHistory() {
        jdbc.update("""
                INSERT INTO watch_attempt (
                    attempt_id, resource_reference, source_revision, target_url,
                    lease_token, claimed_at, lease_expires_at)
                SELECT gen_random_uuid(), ?, 1, ?, gen_random_uuid(),
                       TIMESTAMPTZ '2026-08-01 00:00:00Z' + n * INTERVAL '1 second',
                       TIMESTAMPTZ '2026-08-01 00:00:30Z' + n * INTERVAL '1 second'
                FROM generate_series(1, 100) n
                """, REFERENCE, TARGET);
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, previous_health, current_health,
                    changed_at, delivery_status, delivery_attempt, delivered_at,
                    last_delivery_outcome, last_http_status_code)
                SELECT gen_random_uuid(), ?, 1, 'UNKNOWN', 'HEALTHY',
                       TIMESTAMPTZ '2026-08-01 00:00:00Z' + n * INTERVAL '1 second',
                       'DELIVERED', 1, TIMESTAMPTZ '2026-08-01 00:00:01Z' + n * INTERVAL '1 second',
                       'DELIVERED', 200
                FROM generate_series(1, 100) n
                """, REFERENCE);
    }

    private List<List<Map<String, Object>>> databaseRows() {
        return List.of(
                jdbc.queryForList("SELECT * FROM watch_monitor ORDER BY resource_reference"),
                jdbc.queryForList("SELECT * FROM watch_attempt ORDER BY attempt_id"),
                jdbc.queryForList("SELECT * FROM watch_result ORDER BY attempt_id"),
                jdbc.queryForList("SELECT * FROM watch_health_change_event ORDER BY event_id"),
                jdbc.queryForList("SELECT * FROM watch_health_change_event_backlog"));
    }

    private ToolResult runTool(String... arguments) throws Exception {
        Path output = Files.createTempFile(temporary, "diagnostics-", ".log");
        List<String> command = new ArrayList<>(List.of("bash", "../ops/staging-monitor-diagnostics.sh"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("진단 도구 종료").isTrue();
            return new ToolResult(process.exitValue(), Files.readString(output));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private record ToolResult(int status, String output) {}
}
