package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

class JdbcHealthChangeEventDeliveryPersistenceIntegrationTest
        extends MonitoringPersistenceIntegrationTestSupport {

    private JdbcHealthChangeEventDeliveryAdapter deliveryAdapter;

    @BeforeEach
    void setUpDeliveryAdapter() {
        deliveryAdapter = newDeliveryAdapter();
    }

    @Test
    void deliveryLeaseRecoversAtExpiryAndFinalizationIsTokenSafeAndIdempotent() {
        UUID eventId = createDeliveryEvent("resource:delivery-lease");

        ClaimedHealthChangeEvent first = claimOneDelivery();

        assertThat(first.payload().eventId()).isEqualTo(eventId);
        assertThat(first.deliveryAttempt()).isEqualTo(1);
        assertThat(first.recoveredLease()).isFalse();
        assertThat(first.payload().attemptId()).isPresent();
        assertThat(deliveryAdapter.getBacklogSnapshot().pendingCount()).isEqualTo(1);
        assertThat(deliveryAdapter.claimPendingEvent(LEASE))
                .isEmpty();

        jdbc.update("""
                UPDATE watch_health_change_event
                SET next_attempt_at = changed_at,
                    delivery_lease_expires_at = changed_at + INTERVAL '1 microsecond'
                WHERE event_id = ?
                """, eventId);
        ClaimedHealthChangeEvent recovered = claimOneDelivery();
        Instant recoveredAt = recovered.claimedAt();
        Instant recoveredLeaseExpiresAt = jdbc.queryForObject("""
                SELECT delivery_lease_expires_at
                FROM watch_health_change_event
                WHERE event_id = ?
                """, java.time.OffsetDateTime.class, eventId).toInstant();
        assertThat(recoveredLeaseExpiresAt).isEqualTo(recoveredAt.plus(LEASE));
        assertThat(recovered.payload()).isEqualTo(first.payload());
        assertThat(recovered.deliveryAttempt()).isEqualTo(2);
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(recovered.recoveredLease()).isTrue();

        assertThat(deliveryAdapter.finalizeDelivery(deliveredFinalization(first, recoveredAt.plusSeconds(1))))
                .isEqualTo(EventDeliveryFinalizationStatus.STALE_CLAIM);
        EventDeliveryFinalization delivered = deliveredFinalization(recovered, recoveredAt.plusSeconds(2));
        assertThat(deliveryAdapter.finalizeDelivery(delivered))
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);
        assertThat(deliveryAdapter.finalizeDelivery(delivered))
                .isEqualTo(EventDeliveryFinalizationStatus.ALREADY_DELIVERED);

        EventDeliveryBacklogSnapshot deliveredBacklog = deliveryAdapter.getBacklogSnapshot();
        assertThat(deliveredBacklog.pendingCount()).isZero();
        assertThat(deliveredBacklog.oldestChangedAt()).isEmpty();
        assertThat(deliveryAdapter.claimPendingEvent(LEASE))
                .isEmpty();
        assertThat(jdbc.queryForMap("""
                        SELECT delivery_status, delivery_attempt, last_delivery_outcome, last_http_status_code
                        FROM watch_health_change_event
                        WHERE event_id = ?
                        """, eventId))
                .containsEntry("delivery_status", "DELIVERED")
                .containsEntry("delivery_attempt", 2)
                .containsEntry("last_delivery_outcome", "DELIVERED")
                .containsEntry("last_http_status_code", 204);
    }

    @Test
    void failedDeliveryPersistsBoundedOutcomeAndBecomesClaimableAtRetryBoundary() {
        UUID eventId = createDeliveryEvent("resource:delivery-retry");
        ClaimedHealthChangeEvent first = claimOneDelivery();
        Instant completedAt = first.claimedAt().plusSeconds(1);
        Instant retryAt = completedAt.plusSeconds(30);

        EventDeliveryFinalization failed = new EventDeliveryFinalization(
                first.payload().eventId(),
                first.leaseToken(),
                EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE),
                completedAt,
                retryAt);
        assertThat(deliveryAdapter.finalizeDelivery(failed))
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);
        EventDeliveryBacklogSnapshot retryBacklog = deliveryAdapter.getBacklogSnapshot();
        assertThat(retryBacklog.pendingCount()).isEqualTo(1);
        assertThat(retryBacklog.oldestChangedAt()).contains(first.payload().changedAt());

        assertThat(deliveryAdapter.claimPendingEvent(LEASE))
                .isEmpty();
        jdbc.update("""
                UPDATE watch_health_change_event
                SET next_attempt_at = transaction_timestamp()
                WHERE event_id = ?
                """, eventId);
        ClaimedHealthChangeEvent retried = claimOneDelivery();
        assertThat(retried.payload().eventId()).isEqualTo(eventId);
        assertThat(retried.deliveryAttempt()).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                        SELECT delivery_status, last_delivery_outcome, last_http_status_code
                        FROM watch_health_change_event
                        WHERE event_id = ?
                        """, eventId))
                .containsEntry("delivery_status", "PENDING")
                .containsEntry("last_delivery_outcome", "DNS_FAILURE")
                .containsEntry("last_http_status_code", null);
    }

    @Test
    void concurrentDeliveryClaimersReceiveDisjointEvents() throws Exception {
        UUID firstEvent = createDeliveryEvent("resource:delivery-concurrent-1");
        UUID secondEvent = createDeliveryEvent("resource:delivery-concurrent-2");
        JdbcHealthChangeEventDeliveryAdapter anotherAdapter = newDeliveryAdapter();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Optional<ClaimedHealthChangeEvent>> first = null;
        Future<Optional<ClaimedHealthChangeEvent>> second = null;
        try {
            first = executor.submit(
                    () -> claimDeliveriesConcurrently(deliveryAdapter, ready, start));
            second = executor.submit(
                    () -> claimDeliveriesConcurrently(anotherAdapter, ready, start));
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ClaimedHealthChangeEvent firstClaim = first.get(
                            CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .orElseThrow();
            ClaimedHealthChangeEvent secondClaim = second.get(
                            CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .orElseThrow();
            assertThat(List.of(
                            firstClaim.payload().eventId(),
                            secondClaim.payload().eventId()))
                    .containsExactlyInAnyOrder(firstEvent, secondEvent);
        } finally {
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    @Test
    void claimPendingEventSkipsLockedLeadingEventWithoutWaiting() throws Exception {
        UUID lockedEvent = createDeliveryEvent("resource:delivery-locked-leading");
        UUID followingEvent = createDeliveryEvent("resource:delivery-after-locked");
        DataSourceTransactionManager lockTransactionManager =
                new DataSourceTransactionManager(testDataSource);
        JdbcTemplate lockJdbc = new JdbcTemplate(testDataSource);
        JdbcHealthChangeEventDeliveryAdapter competingAdapter = newDeliveryAdapter();
        TransactionStatus lockTransaction = lockTransactionManager.getTransaction(
                new DefaultTransactionDefinition());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Optional<ClaimedHealthChangeEvent>> claimFuture = null;
        try {
            assertThat(lockLeadingDueEvent(lockJdbc)).isEqualTo(lockedEvent);
            claimFuture = executor.submit(() -> competingAdapter.claimPendingEvent(LEASE));

            ClaimedHealthChangeEvent claim = claimFuture.get(
                            CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .orElseThrow();

            assertThat(claim.payload().eventId()).isEqualTo(followingEvent);
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
    void sameLeaseConcurrentFinalizationAppliesOnceAndTokenMustMatch() throws Exception {
        UUID eventId = createDeliveryEvent("resource:delivery-concurrent-finalize");
        ClaimedHealthChangeEvent claimed = claimOneDelivery();
        Instant completedAt = claimed.claimedAt().plusSeconds(1);
        EventDeliveryFinalization valid = deliveredFinalization(claimed, completedAt);
        EventDeliveryFinalization wrongToken = new EventDeliveryFinalization(
                eventId,
                UUID.randomUUID(),
                valid.observation(),
                completedAt,
                null);

        assertThat(deliveryAdapter.finalizeDelivery(wrongToken))
                .isEqualTo(EventDeliveryFinalizationStatus.STALE_CLAIM);

        JdbcHealthChangeEventDeliveryAdapter anotherAdapter = newDeliveryAdapter();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<EventDeliveryFinalizationStatus> first = null;
        Future<EventDeliveryFinalizationStatus> second = null;
        try {
            first = executor.submit(
                    () -> finalizeDeliveryConcurrently(deliveryAdapter, valid, ready, start));
            second = executor.submit(
                    () -> finalizeDeliveryConcurrently(anotherAdapter, valid, ready, start));
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                            first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                            second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            EventDeliveryFinalizationStatus.APPLIED,
                            EventDeliveryFinalizationStatus.ALREADY_DELIVERED);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM watch_health_change_event
                    WHERE event_id = ?
                      AND delivery_status = 'DELIVERED'
                      AND delivery_attempt = 1
                    """, Integer.class, eventId)).isEqualTo(1);
        } finally {
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    @Test
    void saturatedDeliveryAttemptDoesNotBlockLaterPendingEvents() {
        monitorPersistence.synchronize(
                SynchronizeMonitorCommand.inactive(
                        new ResourceReference("resource:saturated-attempt"), new SourceRevision(1)),
                BASE_TIME);
        UUID saturated = insertPendingEvent(
                "resource:saturated-attempt", BASE_TIME.minusSeconds(2), Integer.MAX_VALUE);
        UUID following = insertPendingEvent(
                "resource:saturated-attempt", BASE_TIME.minusSeconds(1), 3);

        List<ClaimedHealthChangeEvent> claims = List.of(
                claimOneDelivery(),
                claimOneDelivery());

        assertThat(claims)
                .extracting(
                        claim -> claim.payload().eventId(),
                        ClaimedHealthChangeEvent::deliveryAttempt)
                .containsExactlyInAnyOrder(
                        tuple(saturated, Integer.MAX_VALUE),
                        tuple(following, 4));
    }

    @Test
    void deliveredEventRetentionIsBoundedAndNeverDeletesPendingEvents() {
        monitorPersistence.synchronize(
                SynchronizeMonitorCommand.inactive(
                        new ResourceReference("resource:event-retention"), new SourceRevision(1)),
                BASE_TIME);
        Instant cutoff = BASE_TIME.plus(Duration.ofDays(30));
        UUID oldOne = insertDeliveredEvent("resource:event-retention", BASE_TIME, cutoff.minusSeconds(2));
        UUID oldTwo = insertDeliveredEvent("resource:event-retention", BASE_TIME.plusSeconds(1), cutoff.minusSeconds(1));
        UUID atCutoff = insertDeliveredEvent("resource:event-retention", BASE_TIME.plusSeconds(2), cutoff);
        UUID afterCutoff = insertDeliveredEvent(
                "resource:event-retention", BASE_TIME.plusSeconds(3), cutoff.plusSeconds(1));
        UUID pending = insertPendingEvent("resource:event-retention", BASE_TIME.minus(Duration.ofDays(90)));

        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isEqualTo(1);
        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isEqualTo(1);
        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isZero();

        assertThat(jdbc.queryForList(
                        "SELECT event_id FROM watch_health_change_event ORDER BY event_id", UUID.class))
                .containsExactlyInAnyOrder(atCutoff, afterCutoff, pending)
                .doesNotContain(oldOne, oldTwo);
        EventDeliveryBacklogSnapshot retainedBacklog = deliveryAdapter.getBacklogSnapshot();
        assertThat(retainedBacklog.pendingCount()).isEqualTo(1);
        assertThat(retainedBacklog.oldestChangedAt())
                .contains(BASE_TIME.minus(Duration.ofDays(90)));
        ClaimedHealthChangeEvent pendingClaim = claimOneDelivery();
        assertThat(pendingClaim.payload().eventId()).isEqualTo(pending);
        assertThat(pendingClaim.payload().attemptId()).isEmpty();
    }

    @Test
    void deliveredEventRetentionSkipsLockedLeadingEventAndPurgesAnotherCandidate() throws Exception {
        String reference = "resource:event-retention-locked";
        monitorPersistence.synchronize(
                SynchronizeMonitorCommand.inactive(
                        new ResourceReference(reference), new SourceRevision(1)),
                BASE_TIME);
        Instant cutoff = BASE_TIME.plus(Duration.ofDays(30));
        UUID locked = insertDeliveredEvent(reference, BASE_TIME, cutoff.minusSeconds(2));
        UUID available = insertDeliveredEvent(
                reference, BASE_TIME.plusSeconds(1), cutoff.minusSeconds(1));

        DataSourceTransactionManager lockTransactionManager =
                new DataSourceTransactionManager(testDataSource);
        JdbcTemplate lockJdbc = new JdbcTemplate(testDataSource);
        TransactionStatus lockTransaction = lockTransactionManager.getTransaction(
                new DefaultTransactionDefinition());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> purgeFuture = null;
        try {
            assertThat(lockJdbc.queryForObject("""
                    SELECT event_id
                    FROM watch_health_change_event
                    WHERE event_id = ?
                    FOR UPDATE
                    """, UUID.class, locked)).isEqualTo(locked);

            JdbcHealthChangeEventDeliveryAdapter competingAdapter = newDeliveryAdapter();
            purgeFuture = executor.submit(() -> competingAdapter.purgeDeliveredEvents(cutoff, 1));

            assertThat(purgeFuture.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isEqualTo(1);
            assertThat(jdbc.queryForList(
                            "SELECT event_id FROM watch_health_change_event ORDER BY event_id", UUID.class))
                    .contains(locked)
                    .doesNotContain(available);
        } finally {
            cancelIfRunning(purgeFuture);
            lockTransactionManager.rollback(lockTransaction);
            shutdownAndAwait(executor);
        }
    }

    private JdbcHealthChangeEventDeliveryAdapter newDeliveryAdapter() {
        return new JdbcHealthChangeEventDeliveryAdapter(
                JdbcClient.create(testDataSource), newTransactionOperations());
    }

    private UUID createDeliveryEvent(String reference) {
        synchronize(reference, 1, "https://" + reference.replace(':', '-') + ".example/path", BASE_TIME);
        ClaimedCheck check = claimOne();
        Instant changedAt = check.claimedAt();
        checkWorkPersistence.finalizeCheck(finalization(
                        check,
                        CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                        changedAt,
                        changedAt.plus(INTERVAL)));
        return jdbc.queryForObject("""
                SELECT event_id
                FROM watch_health_change_event
                WHERE resource_reference = ? AND changed_at = ?
                """, UUID.class, reference, databaseTime(changedAt));
    }

    private ClaimedHealthChangeEvent claimOneDelivery() {
        return deliveryAdapter.claimPendingEvent(LEASE).orElseThrow();
    }

    private EventDeliveryFinalization deliveredFinalization(
            ClaimedHealthChangeEvent event, Instant completedAt) {
        return new EventDeliveryFinalization(
                event.payload().eventId(),
                event.leaseToken(),
                EventDeliveryObservation.forHttpStatus(204),
                completedAt,
                null);
    }

    private Optional<ClaimedHealthChangeEvent> claimDeliveriesConcurrently(
            JdbcHealthChangeEventDeliveryAdapter claimingAdapter,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return claimingAdapter.claimPendingEvent(LEASE);
    }

    private EventDeliveryFinalizationStatus finalizeDeliveryConcurrently(
            JdbcHealthChangeEventDeliveryAdapter finalizingAdapter,
            EventDeliveryFinalization finalization,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return finalizingAdapter.finalizeDelivery(finalization);
    }

    private UUID lockLeadingDueEvent(JdbcTemplate lockJdbc) {
        return lockJdbc.queryForObject("""
                SELECT event_id
                FROM watch_health_change_event
                WHERE delivery_status = 'PENDING'
                  AND next_attempt_at <= transaction_timestamp()
                  AND (delivery_lease_expires_at IS NULL OR delivery_lease_expires_at <= transaction_timestamp())
                ORDER BY next_attempt_at, changed_at, event_id
                LIMIT 1
                FOR UPDATE
                """,
                UUID.class);
    }

    private UUID insertDeliveredEvent(String reference, Instant changedAt, Instant deliveredAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at,
                    delivery_status, delivery_attempt, next_attempt_at,
                    delivered_at, last_delivery_outcome, last_http_status_code
                ) VALUES (?, ?, 1, NULL, 'UNKNOWN', 'HEALTHY', ?, 'DELIVERED', 1, NULL, ?, 'DELIVERED', 204)
                """,
                eventId,
                reference,
                databaseTime(changedAt),
                databaseTime(deliveredAt));
        return eventId;
    }

    private UUID insertPendingEvent(String reference, Instant changedAt) {
        return insertPendingEvent(reference, changedAt, 3);
    }

    private UUID insertPendingEvent(String reference, Instant changedAt, int deliveryAttempt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at,
                    delivery_status, delivery_attempt, next_attempt_at,
                    delivered_at, last_delivery_outcome, last_http_status_code
                ) VALUES (?, ?, 1, NULL, 'HEALTHY', 'UNKNOWN', ?, 'PENDING', ?, ?, NULL, 'DNS_FAILURE', NULL)
                """,
                eventId,
                reference,
                databaseTime(changedAt),
                deliveryAttempt,
                databaseTime(changedAt));
        return eventId;
    }
}
