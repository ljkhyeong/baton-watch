package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.jdbc.JdbcTestUtils.countRowsInTable;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
    void leaseWindowStartsFromTheDatabaseTransactionTime() {
        synchronize("resource:database-time", 1, "https://database-time.example/path", BASE_TIME);
        Instant beforeClaim = databaseClock();

        ClaimedCheck claimed = claimOne();

        Instant afterClaim = databaseClock();
        var lease = jdbc.queryForMap("""
                SELECT claimed_at, lease_expires_at
                FROM watch_attempt
                WHERE attempt_id = ?
                """, claimed.attemptId());
        Instant claimedAt = ((java.sql.Timestamp) lease.get("claimed_at")).toInstant();
        Instant leaseExpiresAt = ((java.sql.Timestamp) lease.get("lease_expires_at")).toInstant();
        assertThat(claimed.scheduledAt()).isEqualTo(BASE_TIME);
        assertThat(claimed.claimedAt()).isEqualTo(claimedAt);
        assertThat(claimedAt).isBetween(beforeClaim, afterClaim);
        assertThat(leaseExpiresAt).isEqualTo(claimedAt.plus(LEASE));
        assertThat(jdbc.queryForObject("""
                        SELECT lease_expires_at
                        FROM watch_monitor
                        WHERE resource_reference = 'resource:database-time'
                        """, OffsetDateTime.class).toInstant())
                .isEqualTo(leaseExpiresAt);
    }

    @Test
    void claimsHistoricalUnsafeTargetWithoutRollingBackOtherDueWork() {
        String historicalTarget = "https://legacy.example/%0d%0aHost:internal";
        synchronize("resource:a-legacy", 1, "https://legacy.example/path", BASE_TIME);
        synchronize("resource:b-current", 1, "https://current.example/path", BASE_TIME);
        jdbc.update(
                "UPDATE watch_monitor SET target_url = ? WHERE resource_reference = ?",
                historicalTarget,
                "resource:a-legacy");

        List<ClaimedCheck> claims = checkWorkPersistence.claimDueChecks(LEASE, 2);

        assertThat(claims)
                .extracting(claim -> claim.targetUrl().value())
                .containsExactly(historicalTarget, "https://current.example/path");
        assertThat(jdbc.queryForList(
                "SELECT resource_reference FROM watch_attempt ORDER BY resource_reference",
                String.class))
                .containsExactly("resource:a-legacy", "resource:b-current");
        assertThat(jdbc.queryForList(
                "SELECT source_revision FROM watch_attempt ORDER BY resource_reference",
                Long.class))
                .containsExactly(1L, 1L);
        assertThat(jdbc.queryForList(
                "SELECT target_url FROM watch_attempt ORDER BY resource_reference",
                String.class))
                .containsExactly(historicalTarget, "https://current.example/path");
    }

    @Test
    void expiredLeaseCanBeRecoveredAndTheOlderAttemptBecomesStale() {
        synchronize("resource:lease", 1, "https://lease.example/path", BASE_TIME);
        ClaimedCheck first = claimOne();

        assertThat(checkWorkPersistence.claimDueChecks(LEASE, 1))
                .isEmpty();
        jdbc.update("""
                UPDATE watch_monitor
                SET lease_expires_at = transaction_timestamp() - INTERVAL '1 second'
                WHERE resource_reference = 'resource:lease'
                """);
        ClaimedCheck recovered = claimOne();
        Instant recoveredAt = recovered.claimedAt();

        assertThat(recovered.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        first,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        recoveredAt.plusSeconds(1),
                        recoveredAt.plusSeconds(61))))
                .isEqualTo(CheckFinalizationStatus.STALE_CLAIM);
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        recovered,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        recoveredAt.plusSeconds(2),
                        recoveredAt.plusSeconds(62))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
        assertThat(countRowsInTable(jdbc, "watch_attempt")).isEqualTo(2);
        assertThat(countRowsInTable(jdbc, "watch_result")).isEqualTo(1);
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
                            firstClaims.getFirst().targetUrl().value(),
                            secondClaims.getFirst().targetUrl().value()))
                    .containsExactlyInAnyOrder(
                            "https://one.example/path", "https://two.example/path");
            assertThat(countRowsInTable(jdbc, "watch_attempt")).isEqualTo(2);
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
            assertThat(lockLeadingDueMonitor(lockJdbc)).isEqualTo(lockedReference);
            claimFuture = executor.submit(() -> competingPersistence.claimDueChecks(LEASE, 1));

            List<ClaimedCheck> claims = claimFuture.get(
                    CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(claims)
                    .extracting(claim -> claim.targetUrl().value())
                    .containsExactly("https://next.example/path");
            assertThat(countRowsInTable(jdbc, "watch_attempt")).isEqualTo(1);
        } finally {
            try {
                cancelIfRunning(claimFuture);
                lockTransactionManager.rollback(lockTransaction);
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
            assertThatThrownBy(() -> checkWorkPersistence.claimDueChecks(LEASE, 2))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(countRowsInTable(jdbc, "watch_attempt")).isZero();
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

        assertThat(checkWorkPersistence.claimDueChecks(LEASE, 2))
                .extracting(claim -> claim.targetUrl().value())
                .containsExactly(
                        "https://batch-one.example/path",
                        "https://batch-two.example/path");
    }

    @Test
    void duplicateAndWrongTokenFinalizationCannotDuplicateResultOrEvent() {
        synchronize("resource:idempotent", 1, "https://idempotent.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne();
        Instant completedAt = claimed.claimedAt().plusSeconds(1);
        CheckFinalization valid = finalization(
                claimed,
                CheckObservation.forHttpStatus(200, Duration.ofMillis(17), 42, 1),
                completedAt,
                completedAt.plus(INTERVAL));
        CheckFinalization wrongToken = new CheckFinalization(
                claimed.attemptId(),
                UUID.randomUUID(),
                valid.observation(),
                valid.completedAt(),
                valid.nextCheckAt());

        assertThat(checkWorkPersistence.finalizeCheck(wrongToken))
                .isEqualTo(CheckFinalizationStatus.STALE_CLAIM);
        assertThat(checkWorkPersistence.finalizeCheck(valid))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
        assertThat(checkWorkPersistence.finalizeCheck(valid))
                .isEqualTo(CheckFinalizationStatus.ALREADY_FINALIZED);

        assertThat(countRowsInTable(jdbc, "watch_result")).isEqualTo(1);
        assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT response_bytes FROM watch_result WHERE attempt_id = ?",
                Long.class,
                claimed.attemptId())).isEqualTo(42L);
    }

    @Test
    void concurrentFinalizationCreatesOneResultAndOneEvent() throws Exception {
        synchronize("resource:concurrent-finalize", 1, "https://finalize.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne();
        Instant completedAt = claimed.claimedAt().plusSeconds(1);
        CheckFinalization finalization = finalization(
                claimed,
                CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                completedAt,
                completedAt.plus(INTERVAL));
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
            assertThat(countRowsInTable(jdbc, "watch_result")).isEqualTo(1);
            assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isEqualTo(1);
        } finally {
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    @Test
    void resultProjectionAndHealthEventRollBackTogetherWhenEventInsertFails() {
        synchronize("resource:atomic", 1, "https://atomic.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne();
        Instant completedAt = claimed.claimedAt().plusSeconds(1);
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at, next_attempt_at
                ) VALUES (?, ?, ?, ?, 'UNKNOWN', 'HEALTHY', ?, ?)
                """,
                UUID.randomUUID(),
                "resource:atomic",
                1L,
                claimed.attemptId(),
                databaseTime(completedAt.minusSeconds(1)),
                databaseTime(completedAt.minusSeconds(1)));

        CheckFinalization finalization = finalization(
                claimed,
                CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                completedAt,
                completedAt.plus(INTERVAL));
        assertThatThrownBy(() -> checkWorkPersistence.finalizeCheck(finalization))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countRowsInTable(jdbc, "watch_result")).isZero();
        MonitorProjection projection = projection("resource:atomic");
        assertThat(projection.health()).isEqualTo(Health.UNKNOWN);
        assertThat(projection.lastOutcome()).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT lease_attempt_id = ?
                FROM watch_monitor
                WHERE resource_reference = ?
                """, Boolean.class, claimed.attemptId(), "resource:atomic")).isTrue();
    }

    @Test
    void retentionIsBoundedStrictlyBeforeCutoffAndKeepsOutboxAttemptFacts() {
        Instant cutoff = Instant.now().plus(Duration.ofDays(1));
        List<ClaimedCheck> attempts = List.of(
                claimed("resource:abandoned"),
                claimed("resource:before"),
                claimed("resource:at"),
                claimed("resource:after"));
        monitorPersistence.synchronize(
                SynchronizeMonitorCommand.inactive(
                        new ResourceReference("resource:abandoned"), new SourceRevision(2)),
                cutoff.minusSeconds(30));

        finalizeAt(attempts.get(1), cutoff.minusSeconds(1));
        finalizeAt(attempts.get(2), cutoff);
        finalizeAt(attempts.get(3), cutoff.plusSeconds(1));

        UUID retainedOutboxAttempt = attempts.get(1).attemptId();
        assertThat(checkWorkPersistence.purgeAttempts(cutoff, 1)).isEqualTo(1);
        assertThat(checkWorkPersistence.purgeAttempts(cutoff, 1)).isEqualTo(1);
        assertThat(checkWorkPersistence.purgeAttempts(cutoff, 1)).isZero();

        assertThat(countRowsInTable(jdbc, "watch_attempt")).isEqualTo(2);
        assertThat(countRowsInTable(jdbc, "watch_result")).isEqualTo(2);
        assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM watch_health_change_event
                WHERE attempt_id = ?
                """, Integer.class, retainedOutboxAttempt)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT attempt_id FROM watch_attempt ORDER BY claimed_at", UUID.class))
                .containsExactly(attempts.get(2).attemptId(), attempts.get(3).attemptId());
    }

    @Test
    void retentionSkipsALockedOldestAttemptAndPurgesAnotherBoundedCandidate() throws Exception {
        Instant cutoff = Instant.now().plus(Duration.ofDays(1));
        ClaimedCheck locked = claimed("resource:purge-locked");
        ClaimedCheck available = claimed("resource:purge-available");
        finalizeAt(locked, cutoff.minusSeconds(2));
        finalizeAt(available, cutoff.minusSeconds(1));

        DataSourceTransactionManager lockTransactionManager =
                new DataSourceTransactionManager(testDataSource);
        JdbcTemplate lockJdbc = new JdbcTemplate(testDataSource);
        TransactionStatus lockTransaction = lockTransactionManager.getTransaction(
                new DefaultTransactionDefinition());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> purgeFuture = null;
        try {
            assertThat(lockJdbc.queryForObject("""
                    SELECT attempt_id
                    FROM watch_attempt
                    WHERE attempt_id = ?
                    FOR UPDATE
                    """, UUID.class, locked.attemptId())).isEqualTo(locked.attemptId());

            JdbcCheckWorkPersistenceAdapter competingPersistence = newCheckWorkPersistenceAdapter();
            purgeFuture = executor.submit(() -> competingPersistence.purgeAttempts(cutoff, 1));

            assertThat(purgeFuture.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForList(
                            "SELECT attempt_id FROM watch_attempt ORDER BY attempt_id", UUID.class))
                    .contains(locked.attemptId())
                    .doesNotContain(available.attemptId());
        } finally {
            cancelIfRunning(purgeFuture);
            lockTransactionManager.rollback(lockTransaction);
            shutdownAndAwait(executor);
        }
    }

    private List<ClaimedCheck> claimChecksConcurrently(
            JdbcCheckWorkPersistenceAdapter claimingAdapter,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return claimingAdapter.claimDueChecks(LEASE, 1);
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

    private String lockLeadingDueMonitor(JdbcTemplate lockJdbc) {
        return lockJdbc.queryForObject("""
                SELECT resource_reference
                FROM watch_monitor
                WHERE monitor_status = 'ACTIVE'
                  AND next_check_at <= transaction_timestamp()
                  AND (lease_expires_at IS NULL OR lease_expires_at <= transaction_timestamp())
                ORDER BY next_check_at, resource_reference
                LIMIT 1
                FOR UPDATE
                """,
                String.class);
    }

    private ClaimedCheck claimed(String reference) {
        synchronize(reference, 1, "https://" + reference.replace(':', '-') + ".example/path", BASE_TIME);
        return claimOne();
    }

    private void finalizeAt(ClaimedCheck claimed, Instant completedAt) {
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        claimed,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        completedAt,
                        completedAt.plus(INTERVAL))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
    }

    private Instant databaseClock() {
        return jdbc.queryForObject(
                "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }
}
