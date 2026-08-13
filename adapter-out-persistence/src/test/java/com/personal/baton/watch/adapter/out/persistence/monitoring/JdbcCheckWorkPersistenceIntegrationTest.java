package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

class JdbcCheckWorkPersistenceIntegrationTest extends MonitoringPersistenceIntegrationTestSupport {

    @Test
    void claimsHistoricalUnsafeTargetWithoutRollingBackOtherDueWork() {
        String historicalTarget = "https://legacy.example/%0d%0aHost:internal";
        synchronize("resource:a-legacy", 1, "https://legacy.example/path", BASE_TIME);
        synchronize("resource:b-current", 1, "https://current.example/path", BASE_TIME);
        jdbc.update(
                "UPDATE watch_monitor SET target_url = ? WHERE resource_reference = ?",
                historicalTarget,
                "resource:a-legacy");

        List<ClaimedCheck> claims = checkWorkPersistence.claimDueChecks(
                BASE_TIME, BASE_TIME.plus(LEASE), 2);

        assertThat(claims)
                .extracting(claim -> claim.resourceReference().value())
                .containsExactly("resource:a-legacy", "resource:b-current");
        assertThat(claims)
                .extracting(claim -> claim.targetUrl().value())
                .containsExactly(historicalTarget, "https://current.example/path");
        assertThat(count("watch_attempt")).isEqualTo(2);
    }

    @Test
    void expiredLeaseCanBeRecoveredAndTheOlderAttemptBecomesStale() {
        synchronize("resource:lease", 1, "https://lease.example/path", BASE_TIME);
        ClaimedCheck first = claimOne(BASE_TIME);

        assertThat(checkWorkPersistence.claimDueChecks(
                        BASE_TIME.plusSeconds(29), BASE_TIME.plusSeconds(59), 1))
                .isEmpty();
        ClaimedCheck recovered = claimOne(BASE_TIME.plusSeconds(30));

        assertThat(recovered.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        first,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        BASE_TIME.plusSeconds(31),
                        BASE_TIME.plusSeconds(91))))
                .isEqualTo(CheckFinalizationStatus.STALE_CLAIM);
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        recovered,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        BASE_TIME.plusSeconds(32),
                        BASE_TIME.plusSeconds(92))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
        assertThat(count("watch_attempt")).isEqualTo(2);
        assertThat(count("watch_result")).isEqualTo(1);
    }

    @Test
    void concurrentCheckClaimersReceiveDisjointMonitors() throws Exception {
        synchronize("resource:check-concurrent-1", 1, "https://one.example/path", BASE_TIME);
        synchronize("resource:check-concurrent-2", 1, "https://two.example/path", BASE_TIME);
        JdbcCheckWorkPersistenceAdapter anotherPersistence = newCheckWorkPersistenceAdapter();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<List<ClaimedCheck>> first = null;
        Future<List<ClaimedCheck>> second = null;
        try {
            first = executor.submit(
                    () -> claimChecksConcurrently(checkWorkPersistence, ready, start));
            second = executor.submit(
                    () -> claimChecksConcurrently(anotherPersistence, ready, start));
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ClaimedCheck> firstClaims = first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<ClaimedCheck> secondClaims = second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(firstClaims).hasSize(1);
            assertThat(secondClaims).hasSize(1);
            assertThat(List.of(
                            firstClaims.getFirst().resourceReference().value(),
                            secondClaims.getFirst().resourceReference().value()))
                    .containsExactlyInAnyOrder(
                            "resource:check-concurrent-1", "resource:check-concurrent-2");
            assertThat(count("watch_attempt")).isEqualTo(2);
        } finally {
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    @Test
    void claimDueChecksSkipsLockedLeadingMonitorWithoutWaiting() throws Exception {
        String lockedReference = "resource:check-locked-leading";
        String nextReference = "resource:check-after-locked";
        synchronize(
                lockedReference,
                1,
                "https://locked.example/path",
                BASE_TIME.minusSeconds(1));
        synchronize(nextReference, 1, "https://next.example/path", BASE_TIME);
        DataSourceTransactionManager lockTransactionManager =
                new DataSourceTransactionManager(testDataSource);
        JdbcTemplate lockJdbc = new JdbcTemplate(testDataSource);
        JdbcCheckWorkPersistenceAdapter competingPersistence = newCheckWorkPersistenceAdapter();
        TransactionStatus lockTransaction = lockTransactionManager.getTransaction(
                new DefaultTransactionDefinition());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<List<ClaimedCheck>> claimFuture = null;
        try {
            assertThat(lockLeadingDueMonitor(lockJdbc, BASE_TIME)).isEqualTo(lockedReference);
            claimFuture = executor.submit(() -> competingPersistence.claimDueChecks(
                    BASE_TIME, BASE_TIME.plus(LEASE), 1));

            List<ClaimedCheck> claims = claimFuture.get(
                    CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(lockTransaction.isCompleted()).isFalse();
            assertThat(claims)
                    .extracting(claim -> claim.resourceReference().value())
                    .containsExactly(nextReference);
            assertThat(count("watch_attempt")).isEqualTo(1);
        } finally {
            try {
                if (claimFuture != null && !claimFuture.isDone()) {
                    claimFuture.cancel(true);
                }
                if (!lockTransaction.isCompleted()) {
                    lockTransactionManager.rollback(lockTransaction);
                }
            } finally {
                shutdownAndAwait(executor);
            }
        }
    }

    @Test
    void batchClaimRollsBackEveryAttemptAndLeaseWhenLaterInsertFails() {
        String firstReference = "resource:check-batch-rollback-1";
        String secondReference = "resource:check-batch-rollback-2";
        synchronize(firstReference, 1, "https://batch-one.example/path", BASE_TIME);
        synchronize(secondReference, 1, "https://batch-two.example/path", BASE_TIME);
        jdbc.execute("""
                ALTER TABLE watch_attempt
                ADD CONSTRAINT ck_test_check_claim_failure
                CHECK (resource_reference <> 'resource:check-batch-rollback-2')
                """);

        try {
            assertThatThrownBy(() -> checkWorkPersistence.claimDueChecks(
                            BASE_TIME, BASE_TIME.plus(LEASE), 2))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(count("watch_attempt")).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM watch_monitor
                    WHERE resource_reference IN (?, ?)
                      AND (
                          lease_token IS NOT NULL
                          OR lease_attempt_id IS NOT NULL
                          OR lease_expires_at IS NOT NULL
                      )
                    """, Integer.class, firstReference, secondReference)).isZero();
        } finally {
            jdbc.execute("""
                    ALTER TABLE watch_attempt
                    DROP CONSTRAINT ck_test_check_claim_failure
                    """);
        }

        assertThat(checkWorkPersistence.claimDueChecks(
                        BASE_TIME, BASE_TIME.plus(LEASE), 2))
                .extracting(claim -> claim.resourceReference().value())
                .containsExactly(firstReference, secondReference);
    }

    @Test
    void duplicateAndWrongTokenFinalizationCannotDuplicateResultOrEvent() {
        synchronize("resource:idempotent", 1, "https://idempotent.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        CheckFinalization valid = finalization(
                claimed,
                CheckObservation.forHttpStatus(200, Duration.ofMillis(17), 42, 1),
                BASE_TIME.plusSeconds(1),
                BASE_TIME.plusSeconds(61));
        CheckFinalization wrongToken = new CheckFinalization(
                claimed.attemptId(),
                UUID.randomUUID(),
                claimed.resourceReference(),
                claimed.sourceRevision(),
                valid.observation(),
                valid.completedAt(),
                valid.nextCheckAt());

        assertThat(checkWorkPersistence.finalizeCheck(wrongToken))
                .isEqualTo(CheckFinalizationStatus.STALE_CLAIM);
        assertThat(checkWorkPersistence.finalizeCheck(valid))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
        assertThat(checkWorkPersistence.finalizeCheck(valid))
                .isEqualTo(CheckFinalizationStatus.ALREADY_FINALIZED);

        assertThat(count("watch_result")).isEqualTo(1);
        assertThat(count("watch_health_change_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT response_bytes FROM watch_result WHERE attempt_id = ?",
                Long.class,
                claimed.attemptId())).isEqualTo(42L);
    }

    @Test
    void concurrentFinalizationCreatesOneResultAndOneEvent() throws Exception {
        synchronize("resource:concurrent-finalize", 1, "https://finalize.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        CheckFinalization finalization = finalization(
                claimed,
                CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                BASE_TIME.plusSeconds(1),
                BASE_TIME.plusSeconds(61));
        JdbcCheckWorkPersistenceAdapter anotherPersistence = newCheckWorkPersistenceAdapter();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<CheckFinalizationStatus> first = null;
        Future<CheckFinalizationStatus> second = null;
        try {
            first = executor.submit(
                    () -> finalizeConcurrently(checkWorkPersistence, finalization, ready, start));
            second = executor.submit(
                    () -> finalizeConcurrently(anotherPersistence, finalization, ready, start));
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            CheckFinalizationStatus firstStatus = first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CheckFinalizationStatus secondStatus = second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(List.of(firstStatus, secondStatus))
                    .containsExactlyInAnyOrder(
                            CheckFinalizationStatus.APPLIED,
                            CheckFinalizationStatus.ALREADY_FINALIZED);
            assertThat(count("watch_result")).isEqualTo(1);
            assertThat(count("watch_health_change_event")).isEqualTo(1);
        } finally {
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    @Test
    void resultProjectionAndHealthEventRollBackTogetherWhenEventInsertFails() {
        synchronize("resource:atomic", 1, "https://atomic.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at, next_attempt_at
                ) VALUES (?, ?, ?, ?, 'UNKNOWN', 'HEALTHY', ?, ?)
                """,
                UUID.randomUUID(),
                claimed.resourceReference().value(),
                claimed.sourceRevision().value(),
                claimed.attemptId(),
                OffsetDateTime.ofInstant(BASE_TIME.minusSeconds(1), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE_TIME.minusSeconds(1), ZoneOffset.UTC));

        CheckFinalization finalization = finalization(
                claimed,
                CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                BASE_TIME.plusSeconds(1),
                BASE_TIME.plusSeconds(61));
        assertThatThrownBy(() -> checkWorkPersistence.finalizeCheck(finalization))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("watch_result")).isZero();
        MonitorProjection projection = projection("resource:atomic");
        assertThat(projection.health()).isEqualTo(Health.UNKNOWN);
        assertThat(projection.lastOutcome()).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT lease_attempt_id = ?
                FROM watch_monitor
                WHERE resource_reference = ?
                """, Boolean.class, claimed.attemptId(), claimed.resourceReference().value())).isTrue();
    }

    @Test
    void retentionIsBoundedStrictlyBeforeCutoffAndKeepsOutboxAttemptFacts() {
        Instant cutoff = BASE_TIME.plus(Duration.ofDays(30));
        List<ClaimedCheck> attempts = List.of(
                claimed("resource:abandoned", BASE_TIME),
                claimed("resource:before", BASE_TIME.plusSeconds(1)),
                claimed("resource:at", BASE_TIME.plusSeconds(2)),
                claimed("resource:after", BASE_TIME.plusSeconds(3)));
        monitorPersistence.synchronize(
                SynchronizeMonitorCommand.inactive(
                        attempts.getFirst().resourceReference(), new SourceRevision(2)),
                BASE_TIME.plusSeconds(31));

        finalizeAt(attempts.get(1), cutoff.minusSeconds(1));
        finalizeAt(attempts.get(2), cutoff);
        finalizeAt(attempts.get(3), cutoff.plusSeconds(1));

        UUID retainedOutboxAttempt = attempts.get(1).attemptId();
        assertThat(checkWorkPersistence.purgeAttempts(cutoff, 1)).isEqualTo(1);
        assertThat(checkWorkPersistence.purgeAttempts(cutoff, 1)).isEqualTo(1);
        assertThat(checkWorkPersistence.purgeAttempts(cutoff, 1)).isZero();

        assertThat(count("watch_attempt")).isEqualTo(2);
        assertThat(count("watch_result")).isEqualTo(2);
        assertThat(count("watch_health_change_event")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM watch_health_change_event
                WHERE attempt_id = ?
                """, Integer.class, retainedOutboxAttempt)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT attempt_id FROM watch_attempt ORDER BY claimed_at", UUID.class))
                .containsExactly(attempts.get(2).attemptId(), attempts.get(3).attemptId());
    }

    private List<ClaimedCheck> claimChecksConcurrently(
            JdbcCheckWorkPersistenceAdapter claimingAdapter,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return claimingAdapter.claimDueChecks(BASE_TIME, BASE_TIME.plus(LEASE), 1);
    }

    private CheckFinalizationStatus finalizeConcurrently(
            JdbcCheckWorkPersistenceAdapter finalizingAdapter,
            CheckFinalization finalization,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return finalizingAdapter.finalizeCheck(finalization);
    }

    private String lockLeadingDueMonitor(
            JdbcTemplate lockJdbc, Instant claimedAt) {
        return lockJdbc.queryForObject("""
                SELECT resource_reference
                FROM watch_monitor
                WHERE monitor_status = 'ACTIVE'
                  AND next_check_at <= ?
                  AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                ORDER BY next_check_at, resource_reference
                LIMIT 1
                FOR UPDATE
                """,
                String.class,
                databaseTime(claimedAt),
                databaseTime(claimedAt));
    }

    private ClaimedCheck claimed(String reference, Instant claimedAt) {
        synchronize(reference, 1, "https://" + reference.replace(':', '-') + ".example/path", claimedAt);
        return claimOne(claimedAt);
    }

    private void finalizeAt(ClaimedCheck claimed, Instant completedAt) {
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        claimed,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        completedAt,
                        completedAt.plus(INTERVAL))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
    }
}
