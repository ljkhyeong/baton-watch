package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.jdbc.JdbcTestUtils.countRowsInTable;

import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcMonitorPersistenceIntegrationTest extends MonitoringPersistenceIntegrationTestSupport {

    @Test
    void synchronizeEnforcesMonotonicRevisionAndEqualRevisionPayloadRules() {
        SynchronizationResult created = synchronize("resource:revision", 5, "https://one.example/path", BASE_TIME);

        assertThat(created.status()).isEqualTo(SynchronizationStatus.APPLIED);
        assertThat(created.projection().health()).isEqualTo(Health.UNKNOWN);
        assertThat(created.projection().nextCheckAt()).contains(BASE_TIME);

        SynchronizationResult stale = synchronize(
                "resource:revision", 4, "https://stale.example/path", BASE_TIME.plusSeconds(1));
        SynchronizationResult unchanged = synchronize(
                "resource:revision", 5, "https://one.example/path", BASE_TIME.plusSeconds(2));
        SynchronizationResult conflict = synchronize(
                "resource:revision", 5, "https://conflict.example/path", BASE_TIME.plusSeconds(3));

        assertThat(stale.status()).isEqualTo(SynchronizationStatus.STALE_REVISION);
        assertThat(unchanged.status()).isEqualTo(SynchronizationStatus.UNCHANGED);
        assertThat(conflict.status()).isEqualTo(SynchronizationStatus.REVISION_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT source_revision FROM watch_monitor WHERE resource_reference = ?",
                Long.class,
                "resource:revision"))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                "SELECT target_url FROM watch_monitor WHERE resource_reference = ?",
                String.class,
                "resource:revision"))
                .isEqualTo("https://one.example/path");
        assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isZero();
    }

    @Test
    void concurrentInitialSynchronizationReturnsAppliedThenIdempotentInsteadOfPrimaryKeyFailure() throws Exception {
        SynchronizeMonitorCommand command = SynchronizeMonitorCommand.active(
                new ResourceReference("resource:concurrent-sync"),
                new SourceRevision(1),
                new TargetUrl("https://concurrent.example/path"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<SynchronizationStatus> first = null;
        Future<SynchronizationStatus> second = null;
        try {
            first = executor.submit(() -> synchronizeConcurrently(command, ready, start));
            second = executor.submit(() -> synchronizeConcurrently(command, ready, start));
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            SynchronizationStatus firstStatus = first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            SynchronizationStatus secondStatus = second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(List.of(firstStatus, secondStatus))
                    .containsExactlyInAnyOrder(SynchronizationStatus.APPLIED, SynchronizationStatus.UNCHANGED);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM watch_monitor
                    WHERE resource_reference = 'resource:concurrent-sync'
                    """, Integer.class))
                    .isEqualTo(1);
        } finally {
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    @Test
    void targetChangeResetsProjectionDueNowInvalidatesLeaseAndRecordsHealthChange() {
        synchronize("resource:target-change", 1, "https://one.example/path", BASE_TIME);
        ClaimedCheck first = claimOne(BASE_TIME);
        Instant completedAt = BASE_TIME.plusSeconds(1);
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        first,
                        CheckObservation.forHttpStatus(204, Duration.ZERO, 0, 0),
                        completedAt,
                        completedAt.plus(INTERVAL))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);

        ClaimedCheck inFlight = claimOne(completedAt.plus(INTERVAL));
        Instant changedAt = completedAt.plus(INTERVAL).plusSeconds(1);
        SynchronizationResult changed = synchronize(
                "resource:target-change", 2, "https://two.example/path", changedAt);

        assertThat(changed.projection().health()).isEqualTo(Health.UNKNOWN);
        assertThat(changed.projection().lastOutcome()).isEmpty();
        assertThat(changed.projection().lastCheckedAt()).isEmpty();
        assertThat(changed.projection().nextCheckAt()).contains(changedAt);
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        inFlight,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        changedAt.plusSeconds(1),
                        changedAt.plus(INTERVAL))))
                .isEqualTo(CheckFinalizationStatus.STALE_CLAIM);
        assertThat(countRowsInTable(jdbc, "watch_result")).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT previous_health || '->' || current_health
                FROM watch_health_change_event
                WHERE resource_reference = ?
                ORDER BY changed_at
                """, String.class, "resource:target-change"))
                .containsExactly("UNKNOWN->HEALTHY", "HEALTHY->UNKNOWN");
    }

    @Test
    void synchronizationProjectionAndHealthEventRollBackTogetherWhenEventInsertFails() {
        String reference = "resource:sync-event-rollback";
        String originalTarget = "https://original.example/path";
        synchronize(reference, 1, originalTarget, BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        Instant completedAt = BASE_TIME.plusSeconds(1);
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        claimed,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        completedAt,
                        completedAt.plus(INTERVAL))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
        MonitorProjection before = projection(reference);
        Instant updatedAt = jdbc.queryForObject(
                        "SELECT updated_at FROM watch_monitor WHERE resource_reference = ?",
                        OffsetDateTime.class,
                        reference)
                .toInstant();
        Instant lastConclusiveAt = jdbc.queryForObject(
                        "SELECT last_conclusive_at FROM watch_monitor WHERE resource_reference = ?",
                        OffsetDateTime.class,
                        reference)
                .toInstant();
        jdbc.execute("""
                CREATE UNIQUE INDEX ux_test_sync_event_failure
                ON watch_health_change_event (resource_reference)
                WHERE resource_reference = 'resource:sync-event-rollback'
                """);

        Throwable failure = catchThrowable(() -> synchronize(
                reference,
                2,
                "https://changed.example/path",
                completedAt.plusSeconds(1)));
        assertUniqueConstraintViolation(failure, "ux_test_sync_event_failure");

        assertThat(projection(reference)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT target_url FROM watch_monitor WHERE resource_reference = ?",
                String.class,
                reference))
                .isEqualTo(originalTarget);
        assertThat(jdbc.queryForObject(
                        "SELECT updated_at FROM watch_monitor WHERE resource_reference = ?",
                        OffsetDateTime.class,
                        reference)
                .toInstant())
                .isEqualTo(updatedAt);
        assertThat(jdbc.queryForObject(
                        "SELECT last_conclusive_at FROM watch_monitor WHERE resource_reference = ?",
                        OffsetDateTime.class,
                        reference)
                .toInstant())
                .isEqualTo(lastConclusiveAt);
        assertThat(jdbc.queryForList("""
                SELECT previous_health || '->' || current_health
                FROM watch_health_change_event
                WHERE resource_reference = ?
                ORDER BY changed_at
                """, String.class, reference))
                .containsExactly("UNKNOWN->HEALTHY");
    }

    @Test
    void staleSweepTransitionsAtTheCutoffOnceAndPreservesFailureDerivation() {
        synchronize("resource:stale", 1, "https://stale.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        Instant completedAt = BASE_TIME.plusSeconds(1);
        checkWorkPersistence.finalizeCheck(finalization(
                claimed,
                CheckObservation.failure(CheckOutcome.CONNECT_TIMEOUT, Duration.ZERO, 0, 0),
                completedAt,
                completedAt.plus(INTERVAL)));

        assertThat(monitorPersistence.markStaleUnknown(
                        completedAt.minusNanos(1_000), completedAt.plusSeconds(600), 10))
                .isZero();
        assertThat(monitorPersistence.markStaleUnknown(
                        completedAt, completedAt.plusSeconds(600), 10))
                .isEqualTo(1);
        assertThat(monitorPersistence.markStaleUnknown(
                        completedAt, completedAt.plusSeconds(601), 10))
                .isZero();

        MonitorProjection projection = projection("resource:stale");
        assertThat(projection.health()).isEqualTo(Health.UNKNOWN);
        assertThat(projection.consecutiveFailures()).isEqualTo(1);
        assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isEqualTo(2);
    }

    @Test
    void staleProjectionAndHealthEventRollBackTogetherWhenEventInsertFails() {
        String reference = "resource:stale-event-rollback";
        synchronize(reference, 1, "https://stale-rollback.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        Instant completedAt = BASE_TIME.plusSeconds(1);
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        claimed,
                        CheckObservation.failure(CheckOutcome.CONNECT_TIMEOUT, Duration.ZERO, 0, 0),
                        completedAt,
                        completedAt.plus(INTERVAL))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
        MonitorProjection before = projection(reference);
        Instant updatedAt = jdbc.queryForObject(
                        "SELECT updated_at FROM watch_monitor WHERE resource_reference = ?",
                        OffsetDateTime.class,
                        reference)
                .toInstant();
        jdbc.execute("""
                CREATE UNIQUE INDEX ux_test_stale_event_failure
                ON watch_health_change_event (resource_reference)
                WHERE resource_reference = 'resource:stale-event-rollback'
                """);

        Throwable failure = catchThrowable(() -> monitorPersistence.markStaleUnknown(
                completedAt, completedAt.plusSeconds(600), 1));
        assertUniqueConstraintViolation(failure, "ux_test_stale_event_failure");

        assertThat(projection(reference)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT updated_at FROM watch_monitor WHERE resource_reference = ?",
                        OffsetDateTime.class,
                        reference)
                .toInstant())
                .isEqualTo(updatedAt);
        assertThat(jdbc.queryForList("""
                SELECT previous_health || '->' || current_health
                FROM watch_health_change_event
                WHERE resource_reference = ?
                ORDER BY changed_at
                """, String.class, reference))
                .containsExactly("UNKNOWN->DEGRADED");
    }

    private SynchronizationStatus synchronizeConcurrently(
            SynchronizeMonitorCommand command, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return monitorPersistence.synchronize(command, BASE_TIME).status();
    }

    private static void assertUniqueConstraintViolation(
            Throwable failure, String constraintName) {
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(failure).rootCause()
                .isInstanceOfSatisfying(SQLException.class, sqlFailure -> {
                    assertThat(sqlFailure.getSQLState()).isEqualTo("23505");
                    assertThat(sqlFailure.getMessage()).contains(constraintName);
                });
    }

}
