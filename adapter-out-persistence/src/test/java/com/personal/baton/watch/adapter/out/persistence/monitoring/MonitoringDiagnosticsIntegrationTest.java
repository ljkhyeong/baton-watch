package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.CheckStatus;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @ParameterizedTest(name = "다음 점검 {0}초·점유 만료 {1}초: {2}")
    @CsvSource({
        "-86400,,QUEUED",
        "86400,,SCHEDULED",
        "-86400,86400,IN_PROGRESS",
        "86400,86400,IN_PROGRESS",
        "-86400,-86400,QUEUED",
        "86400,-86400,SCHEDULED",
        "0,,INACTIVE"
    })
    void reportsCheckStatusAtObservationTime(
            int nextCheckOffsetSeconds, Integer leaseOffsetSeconds, CheckStatus expected) throws Exception {
        synchronize(REFERENCE, 1, TARGET, BASE_TIME);
        if (expected == CheckStatus.INACTIVE) {
            monitorPersistence.synchronize(SynchronizeMonitorCommand.inactive(
                    new ResourceReference(REFERENCE), new SourceRevision(2)), BASE_TIME.plusSeconds(1));
        } else {
            if (leaseOffsetSeconds != null) {
                claimOne();
                jdbc.update("""
                        UPDATE watch_monitor
                        SET lease_expires_at = statement_timestamp() + ? * INTERVAL '1 second'
                        WHERE resource_reference = ?
                        """, leaseOffsetSeconds, REFERENCE);
            }
            jdbc.update("""
                    UPDATE watch_monitor
                    SET next_check_at = statement_timestamp() + ? * INTERVAL '1 second'
                    WHERE resource_reference = ?
                    """, nextCheckOffsetSeconds, REFERENCE);
        }

        var result = runTool(POSTGRES.getContainerId(), REFERENCE);
        assertThat(result.status()).as(result.output()).isZero();
        var report = JSON.readTree(result.output());
        var observedAt = Instant.parse(report.get("observedAt").asString());
        assertThat(report.at("/monitor/checkStatus").asString())
                .isEqualTo(expected.name())
                .isEqualTo(projection(REFERENCE).checkStatusAt(observedAt).name());
    }

    @ParameterizedTest(name = "다음 전달 {0}초·점유 만료 {1}초: {2}")
    @CsvSource({
        "-172800,,QUEUED",
        "86400,,SCHEDULED",
        "-172800,172800,IN_PROGRESS",
        "86400,172800,IN_PROGRESS",
        "-172800,-86400,QUEUED"
    })
    void reportsDeliveryProgressConsistentWithClaimEligibility(
            int nextAttemptOffsetSeconds, Integer leaseOffsetSeconds, String expected) throws Exception {
        synchronize(REFERENCE, 1, TARGET, BASE_TIME);
        UUID eventId = UUID.randomUUID();
        UUID leaseToken = leaseOffsetSeconds == null ? null : UUID.randomUUID();
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, previous_health, current_health,
                    changed_at, next_attempt_at, delivery_attempt, delivery_lease_token, delivery_lease_expires_at)
                VALUES (?, ?, 1, 'UNKNOWN', 'HEALTHY',
                        statement_timestamp() - INTERVAL '3 days',
                        statement_timestamp() + ? * INTERVAL '1 second', ?, ?,
                        statement_timestamp() + ?::integer * INTERVAL '1 second')
                """, eventId, REFERENCE, nextAttemptOffsetSeconds,
                leaseToken == null ? 0 : 1, leaseToken, leaseOffsetSeconds);
        var before = databaseRows();

        var result = runTool(POSTGRES.getContainerId(), REFERENCE);
        assertThat(result.status()).as(result.output()).isZero();
        var report = JSON.readTree(result.output());
        assertThat(report.at("/deliveries/0/deliveryStatus").asString()).isEqualTo("PENDING");
        assertThat(report.at("/deliveries/0/deliveryProgress").asString()).isEqualTo(expected);
        assertThat(databaseRows()).isEqualTo(before);
        if (leaseToken != null) {
            assertThat(result.output()).doesNotContain(leaseToken.toString());
        }

        var deliveries = new JdbcHealthChangeEventDeliveryAdapter(
                JdbcClient.create(jdbc), newTransactionOperations());
        var claim = deliveries.claimPendingEvent(LEASE);
        assertThat(claim.isPresent()).isEqualTo(expected.equals("QUEUED"));
        claim.ifPresent(event -> assertThat(event.payload().eventId()).isEqualTo(eventId));
    }

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
        assertThat(report.at("/deliveries/1/deliveryProgress").asString()).isEqualTo("DELIVERED");
        assertThat(result.output()).doesNotContain(
                TARGET, REFERENCE, "private.example", "private-fixture", "targetUrl", "leaseToken", "leaseExpiresAt",
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

    @ParameterizedTest(name = "서버 상세 로그 {0}에서 정상·잠금 실패 조회의 식별자 제외")
    @ValueSource(booleans = {false, true})
    void boundsLockWaitWithoutLeakingReferencesToClientOrServerLogs(boolean verbose) throws Exception {
        String reference = "diagnostics-log-" + UUID.randomUUID();
        List<String> loggingSettings = List.of(
                "log_statement", "log_min_duration_statement",
                "log_parameter_max_length", "log_parameter_max_length_on_error");
        int logStart = POSTGRES.getLogs().length();
        try {
            if (verbose) {
                jdbc.execute("ALTER ROLE CURRENT_USER SET log_statement = 'all'");
                jdbc.execute("ALTER ROLE CURRENT_USER SET log_min_duration_statement = 0");
                jdbc.execute("ALTER ROLE CURRENT_USER SET log_parameter_max_length = -1");
                jdbc.execute("ALTER ROLE CURRENT_USER SET log_parameter_max_length_on_error = -1");
            }
            try (var connection = testDataSource.getConnection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("LOCK TABLE watch_attempt IN ACCESS EXCLUSIVE MODE");
                var result = runTool(POSTGRES.getContainerId(), reference);
                assertThat(result.status()).isNotZero();
                assertThat(result.output()).contains("진단 조회 실패").doesNotContain("observedAt", reference);
                connection.rollback();
            }
            var successful = runTool(POSTGRES.getContainerId(), reference);
            assertThat(successful.status()).as(successful.output()).isZero();
            assertThat(successful.output()).doesNotContain(reference);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                String logs = POSTGRES.getLogs().substring(logStart);
                assertThat(logs).contains("canceling statement due to lock timeout")
                        .doesNotContain(reference);
                if (verbose) {
                    assertThat(logs).contains("execute <unnamed>:", "duration:", "statement: COMMIT");
                }
            });
        } finally {
            if (verbose) {
                for (String setting : loggingSettings) {
                    jdbc.execute("ALTER ROLE CURRENT_USER RESET " + setting);
                }
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
