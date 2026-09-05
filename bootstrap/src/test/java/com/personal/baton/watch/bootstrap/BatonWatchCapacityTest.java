package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.personal.baton.watch.adapter.out.external.check.ApacheUrlChecker;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 운영 기본 일정·배치를 유지하고 느린 점검과 보존 정리의 로컬 참고값을 측정한다. */
@SpringBootTest(classes = BatonWatchApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "spring.datasource.password=service-connection-overridden",
        "watch.api-token=capacity-test-token-0123456789abcdef"
})
@Testcontainers
@DirtiesContext
@Tag("capacity")
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class BatonWatchCapacityTest {

    @Container
    @ServiceConnection(name = "postgres")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("baton_watch").withUsername("baton_watch").withPassword("capacity-test");

    @MockitoBean(enforceOverride = true)
    private ApacheUrlChecker checker;

    @Autowired private SynchronizeMonitorUseCase synchronize;
    @Autowired private PurgeAttemptHistoryUseCase purge;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WatchProperties properties;

    @Test
    void measuresTwoCheckCyclesWithDefaultSettingsAndExpiredHistory() {
        int monitors = Integer.getInteger("watch.capacity.monitors", 12);
        int slowMillis = Integer.getInteger("watch.capacity.slow-millis", 5000);
        assertThat(monitors).isBetween(4, 30);
        assertThat(slowMillis).isBetween(100, 5000);
        assertThat(properties.checkBatchSize()).isEqualTo(1);
        assertThat(properties.checkInterval()).isEqualTo(Duration.ofSeconds(60));
        doAnswer(invocation -> {
            TargetUrl target = invocation.getArgument(0);
            int index = Integer.parseInt(target.value().substring(target.value().lastIndexOf('/') + 1));
            int millis = index % 4 == 0 ? slowMillis : 100;
            Thread.sleep(millis);
            return index % 4 == 0
                    ? CheckObservation.failure(CheckOutcome.READ_TIMEOUT, Duration.ofMillis(millis), 0, 0)
                    : CheckObservation.forHttpStatus(200, Duration.ofMillis(millis), 0, 0);
        }).when(checker).check(any());

        Instant startedAt = Instant.now();
        for (int index = 0; index < monitors; index++) {
            synchronize.synchronize(SynchronizeMonitorCommand.active(
                    new ResourceReference("capacity:" + index), new SourceRevision(1),
                    new TargetUrl("https://capacity.invalid/" + index)));
        }
        // 31일 전 이력 표본이다. 30일치 분 단위 운영 데이터 전체를 재현하지 않는다.
        jdbc.update("""
                INSERT INTO watch_attempt (
                    attempt_id, resource_reference, source_revision, target_url,
                    lease_token, claimed_at, lease_expires_at
                ) SELECT gen_random_uuid(), resource_reference, source_revision, target_url,
                    gen_random_uuid(), now() - interval '31 days', now() - interval '31 days' + interval '30 seconds'
                  FROM watch_monitor CROSS JOIN generate_series(1, 120)
                """);
        jdbc.update("""
                INSERT INTO watch_result (
                    attempt_id, outcome, http_status_code, completed_at,
                    duration_seconds, duration_nanos, response_bytes, redirect_count
                ) SELECT attempt_id, 'SUCCESS', 200, claimed_at, 0, 0, 0, 0
                  FROM watch_attempt WHERE claimed_at < now() - interval '30 days'
                """);

        await("기본 설정에서 모니터마다 두 번 점검 완료")
                .pollInterval(Duration.ofSeconds(1)).atMost(Duration.ofMinutes(3))
                .untilAsserted(() -> assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM (
                            SELECT attempt.resource_reference
                            FROM watch_result result JOIN watch_attempt attempt USING (attempt_id)
                            WHERE result.completed_at >= ?
                            GROUP BY attempt.resource_reference HAVING COUNT(*) >= 2
                        ) checked
                        """, Integer.class, Timestamp.from(startedAt))).isEqualTo(monitors));

        var timings = jdbc.queryForMap("""
                WITH checks AS (
                    SELECT resource_reference, completed_at,
                        LAG(completed_at) OVER (PARTITION BY resource_reference ORDER BY completed_at) AS previous_at
                    FROM watch_result JOIN watch_attempt USING (attempt_id)
                    WHERE completed_at >= ?
                ) SELECT
                    EXTRACT(EPOCH FROM (MAX(completed_at) FILTER (WHERE previous_at IS NULL) - ?)) AS first_pass_seconds,
                    MAX(EXTRACT(EPOCH FROM (completed_at - previous_at))) AS maximum_interval_seconds
                  FROM checks
                """, Timestamp.from(startedAt), Timestamp.from(startedAt));
        long cleanupStarted = System.nanoTime();
        int purged = purge.purgeAttemptHistory();
        long cleanupMillis = Duration.ofNanos(System.nanoTime() - cleanupStarted).toMillis();
        assertThat(purged).isEqualTo(properties.maintenanceBatchSize());
        System.out.printf("기본 설정 용량 참고: monitors=%d slowMillis=%d firstPassSeconds=%s "
                        + "maximumIntervalSeconds=%s purgeRows=%d purgeMillis=%d%n",
                monitors, slowMillis, timings.get("first_pass_seconds"),
                timings.get("maximum_interval_seconds"), purged, cleanupMillis);
    }
}
