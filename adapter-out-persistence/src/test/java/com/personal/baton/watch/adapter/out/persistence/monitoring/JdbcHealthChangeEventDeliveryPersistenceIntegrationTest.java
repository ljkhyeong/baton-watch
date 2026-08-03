package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcHealthChangeEventDeliveryPersistenceIntegrationTest
        extends MonitoringPersistenceIntegrationTestSupport {

    private JdbcHealthChangeEventDeliveryAdapter deliveryAdapter;

    @BeforeEach
    void setUpDeliveryAdapter() {
        deliveryAdapter = new JdbcHealthChangeEventDeliveryAdapter(
                jdbc, new DataSourceTransactionManager(testDataSource));
    }

    @Test
    void deliveryLeaseRecoversAtExpiryAndFinalizationIsTokenSafeAndIdempotent() {
        UUID eventId = createDeliveryEvent("resource:delivery-lease", BASE_TIME);
        Instant dueAt = BASE_TIME.plusSeconds(1);

        ClaimedHealthChangeEvent first = claimOneDelivery(dueAt);

        assertThat(first.payload().eventId()).isEqualTo(eventId);
        assertThat(first.deliveryAttempt()).isEqualTo(1);
        assertThat(first.payload().attemptId()).isPresent();
        assertThat(deliveryAdapter.getBacklogSnapshot().pendingCount()).isEqualTo(1);
        assertThat(deliveryAdapter.claimPendingEvents(dueAt.plusSeconds(29), dueAt.plusSeconds(59), 1))
                .isEmpty();

        ClaimedHealthChangeEvent recovered = claimOneDelivery(dueAt.plusSeconds(30));
        assertThat(recovered.payload()).isEqualTo(first.payload());
        assertThat(recovered.deliveryAttempt()).isEqualTo(2);
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());

        assertThat(deliveryAdapter.finalizeDelivery(deliveredFinalization(first, dueAt.plusSeconds(31)))
                        .status())
                .isEqualTo(EventDeliveryFinalizationStatus.STALE_CLAIM);
        EventDeliveryFinalization delivered = deliveredFinalization(recovered, dueAt.plusSeconds(32));
        assertThat(deliveryAdapter.finalizeDelivery(delivered).status())
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);
        assertThat(deliveryAdapter.finalizeDelivery(delivered).status())
                .isEqualTo(EventDeliveryFinalizationStatus.ALREADY_DELIVERED);

        assertThat(deliveryAdapter.getBacklogSnapshot().pendingCount()).isZero();
        assertThat(deliveryAdapter.claimPendingEvents(dueAt.plusSeconds(60), dueAt.plusSeconds(90), 1))
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
        UUID eventId = createDeliveryEvent("resource:delivery-retry", BASE_TIME);
        Instant dueAt = BASE_TIME.plusSeconds(1);
        ClaimedHealthChangeEvent first = claimOneDelivery(dueAt);
        Instant completedAt = dueAt.plusSeconds(1);
        Instant retryAt = completedAt.plusSeconds(30);

        EventDeliveryFinalization failed = new EventDeliveryFinalization(
                first.payload().eventId(),
                first.leaseToken(),
                first.deliveryAttempt(),
                EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE),
                completedAt,
                retryAt);
        assertThat(deliveryAdapter.finalizeDelivery(failed).status())
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);

        assertThat(deliveryAdapter.claimPendingEvents(retryAt.minusNanos(1_000), retryAt.plusSeconds(30), 1))
                .isEmpty();
        ClaimedHealthChangeEvent retried = claimOneDelivery(retryAt);
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
        UUID firstEvent = createDeliveryEvent("resource:delivery-concurrent-1", BASE_TIME);
        UUID secondEvent = createDeliveryEvent("resource:delivery-concurrent-2", BASE_TIME.plusSeconds(2));
        Instant claimedAt = BASE_TIME.plusSeconds(10);
        JdbcHealthChangeEventDeliveryAdapter anotherAdapter = new JdbcHealthChangeEventDeliveryAdapter(
                new JdbcTemplate(testDataSource), new DataSourceTransactionManager(testDataSource));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedHealthChangeEvent>> first = executor.submit(
                    () -> claimDeliveriesConcurrently(deliveryAdapter, claimedAt, ready, start));
            Future<List<ClaimedHealthChangeEvent>> second = executor.submit(
                    () -> claimDeliveriesConcurrently(anotherAdapter, claimedAt, ready, start));
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ClaimedHealthChangeEvent> firstClaims =
                    first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<ClaimedHealthChangeEvent> secondClaims =
                    second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(firstClaims).hasSize(1);
            assertThat(secondClaims).hasSize(1);
            assertThat(List.of(
                            firstClaims.getFirst().payload().eventId(),
                            secondClaims.getFirst().payload().eventId()))
                    .containsExactlyInAnyOrder(firstEvent, secondEvent);
        } finally {
            executor.shutdownNow();
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

        List<ClaimedHealthChangeEvent> claims = deliveryAdapter.claimPendingEvents(
                BASE_TIME, BASE_TIME.plus(LEASE), 2);

        assertThat(claims).extracting(claim -> claim.payload().eventId())
                .containsExactly(saturated, following);
        assertThat(claims).extracting(ClaimedHealthChangeEvent::deliveryAttempt)
                .containsExactly(Integer.MAX_VALUE, 4);
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
        UUID pending = insertPendingEvent("resource:event-retention", BASE_TIME.minus(Duration.ofDays(90)));

        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isEqualTo(1);
        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isEqualTo(1);
        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isZero();

        assertThat(jdbc.queryForList(
                        "SELECT event_id FROM watch_health_change_event ORDER BY event_id", UUID.class))
                .containsExactlyInAnyOrder(atCutoff, pending)
                .doesNotContain(oldOne, oldTwo);
        assertThat(deliveryAdapter.getBacklogSnapshot().pendingCount()).isEqualTo(1);
        assertThat(deliveryAdapter.getBacklogSnapshot().oldestChangedAt())
                .contains(BASE_TIME.minus(Duration.ofDays(90)));
    }

    private UUID createDeliveryEvent(String reference, Instant claimedAt) {
        synchronize(reference, 1, "https://" + reference.replace(':', '-') + ".example/path", claimedAt);
        ClaimedCheck check = claimOne(claimedAt);
        Instant changedAt = claimedAt.plusSeconds(1);
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        check,
                        CheckObservation.forHttpStatus(200),
                        changedAt,
                        changedAt.plus(INTERVAL)))
                .status()).isEqualTo(CheckFinalizationStatus.APPLIED);
        return jdbc.queryForObject("""
                SELECT event_id
                FROM watch_health_change_event
                WHERE resource_reference = ? AND changed_at = ?
                """, UUID.class, reference, databaseTime(changedAt));
    }

    private ClaimedHealthChangeEvent claimOneDelivery(Instant claimedAt) {
        List<ClaimedHealthChangeEvent> claims = deliveryAdapter.claimPendingEvents(
                claimedAt, claimedAt.plus(LEASE), 1);
        assertThat(claims).hasSize(1);
        return claims.getFirst();
    }

    private EventDeliveryFinalization deliveredFinalization(
            ClaimedHealthChangeEvent event, Instant completedAt) {
        return new EventDeliveryFinalization(
                event.payload().eventId(),
                event.leaseToken(),
                event.deliveryAttempt(),
                EventDeliveryObservation.delivered(204),
                completedAt,
                null);
    }

    private List<ClaimedHealthChangeEvent> claimDeliveriesConcurrently(
            JdbcHealthChangeEventDeliveryAdapter claimingAdapter,
            Instant claimedAt,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return claimingAdapter.claimPendingEvents(claimedAt, claimedAt.plus(LEASE), 1);
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
