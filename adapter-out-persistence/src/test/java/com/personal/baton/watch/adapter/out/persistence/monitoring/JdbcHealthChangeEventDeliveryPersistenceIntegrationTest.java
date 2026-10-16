package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcHealthChangeEventDeliveryPersistenceIntegrationTest
        extends MonitoringPersistenceIntegrationTestSupport {

    @Test
    void deliveryLeaseRecoversAtExpiryAndFinalizationIsTokenSafeAndIdempotent() {
        UUID eventId = createDeliveryEvent("resource:delivery-lease");

        ClaimedHealthChangeEvent first = claimOneDelivery();

        assertThat(first.payload().eventId()).isEqualTo(eventId);
        assertThat(first.deliveryAttempt()).isEqualTo(1);
        assertThat(first.recoveredLease()).isFalse();
        assertThat(first.payload().attemptId()).isPresent();
        assertThat(deliveryPersistence.getBacklogSnapshot().pendingCount()).isEqualTo(1);
        assertThat(deliveryPersistence.claimPendingEvent(LEASE))
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

        assertThat(deliveryPersistence.finalizeDelivery(deliveredFinalization(first, recoveredAt.plusSeconds(1))))
                .isEqualTo(EventDeliveryFinalizationStatus.STALE_CLAIM);
        EventDeliveryFinalization delivered = deliveredFinalization(recovered, recoveredAt.plusSeconds(2));
        assertThat(deliveryPersistence.finalizeDelivery(delivered))
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);
        assertThat(deliveryPersistence.finalizeDelivery(delivered))
                .isEqualTo(EventDeliveryFinalizationStatus.ALREADY_DELIVERED);

        EventDeliveryBacklogSnapshot deliveredBacklog = deliveryPersistence.getBacklogSnapshot();
        assertThat(deliveredBacklog.pendingCount()).isZero();
        assertThat(deliveredBacklog.oldestChangedAt()).isEmpty();
        assertThat(deliveryPersistence.claimPendingEvent(LEASE))
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

    @ParameterizedTest
    @CsvSource({"0, DNS_FAILURE", "429, HTTP_CLIENT_ERROR", "503, HTTP_SERVER_ERROR"})
    void failedDeliveryPersistsBoundedOutcomeAndBecomesClaimableAtRetryBoundary(
            int httpStatus, EventDeliveryOutcome expectedOutcome) {
        UUID eventId = createDeliveryEvent("resource:delivery-retry");
        ClaimedHealthChangeEvent first = claimOneDelivery();
        Instant completedAt = first.claimedAt().plusSeconds(1);
        Instant retryAt = completedAt.plusSeconds(30);

        EventDeliveryFinalization failed = new EventDeliveryFinalization(
                first.payload().eventId(),
                first.leaseToken(),
                httpStatus == 0 ? EventDeliveryObservation.failure(expectedOutcome)
                        : EventDeliveryObservation.forHttpStatus(httpStatus, retryAt),
                completedAt,
                retryAt);
        assertThat(deliveryPersistence.finalizeDelivery(failed))
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);
        EventDeliveryBacklogSnapshot retryBacklog = deliveryPersistence.getBacklogSnapshot();
        assertThat(retryBacklog.pendingCount()).isEqualTo(1);
        assertThat(retryBacklog.oldestChangedAt()).contains(first.payload().changedAt());
        assertThat(jdbc.queryForObject("""
                SELECT next_attempt_at = ? FROM watch_health_change_event WHERE event_id = ?
                """, Boolean.class, databaseTime(retryAt), eventId)).isTrue();

        assertThat(deliveryPersistence.claimPendingEvent(LEASE))
                .isEmpty();
        jdbc.update("""
                UPDATE watch_health_change_event
                SET next_attempt_at = transaction_timestamp()
                WHERE event_id = ?
                """, eventId);
        ClaimedHealthChangeEvent retried = claimOneDelivery();
        assertThat(retried.payload()).isEqualTo(first.payload());
        assertThat(retried.deliveryAttempt()).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                        SELECT delivery_status, last_delivery_outcome, last_http_status_code
                        FROM watch_health_change_event
                        WHERE event_id = ?
                        """, eventId))
                .containsEntry("delivery_status", "PENDING")
                .containsEntry("last_delivery_outcome", expectedOutcome.name())
                .containsEntry("last_http_status_code", httpStatus == 0 ? null : httpStatus);
    }

    @Test
    void concurrentDeliveryClaimersReceiveDisjointEvents() throws Exception {
        UUID firstEvent = createDeliveryEvent("resource:delivery-concurrent-1");
        UUID secondEvent = createDeliveryEvent("resource:delivery-concurrent-2");
        JdbcHealthChangeEventDeliveryAdapter anotherAdapter = newDeliveryAdapter();

        List<Optional<ClaimedHealthChangeEvent>> claims = runConcurrently(
                () -> deliveryPersistence.claimPendingEvent(LEASE),
                () -> anotherAdapter.claimPendingEvent(LEASE));

        assertThat(claims)
                .extracting(claim -> claim.orElseThrow().payload().eventId())
                .containsExactlyInAnyOrder(firstEvent, secondEvent);
    }

    @Test
    void claimPendingEventSkipsLockedLeadingEventWithoutWaiting() throws Exception {
        UUID lockedEvent = createDeliveryEvent("resource:delivery-locked-leading");
        UUID followingEvent = createDeliveryEvent("resource:delivery-after-locked");
        JdbcHealthChangeEventDeliveryAdapter competingAdapter = newDeliveryAdapter();

        ClaimedHealthChangeEvent claim = callWhileLocked(
                () -> assertThat(lockLeadingDueEvent()).isEqualTo(lockedEvent),
                () -> competingAdapter.claimPendingEvent(LEASE)).orElseThrow();

        assertThat(claim.payload().eventId()).isEqualTo(followingEvent);
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

        assertThat(deliveryPersistence.finalizeDelivery(wrongToken))
                .isEqualTo(EventDeliveryFinalizationStatus.STALE_CLAIM);

        JdbcHealthChangeEventDeliveryAdapter anotherAdapter = newDeliveryAdapter();

        assertThat(runConcurrently(
                        () -> deliveryPersistence.finalizeDelivery(valid),
                        () -> anotherAdapter.finalizeDelivery(valid)))
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
    }

    @Test
    void saturatedDeliveryAttemptDoesNotBlockLaterPendingEvents() {
        synchronizeInactive("resource:saturated-attempt", 1, BASE_TIME);
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
        synchronizeInactive("resource:event-retention", 1, BASE_TIME);
        Instant cutoff = BASE_TIME.plus(Duration.ofDays(30));
        UUID oldOne = insertDeliveredEvent("resource:event-retention", BASE_TIME, cutoff.minusSeconds(2));
        UUID oldTwo = insertDeliveredEvent("resource:event-retention", BASE_TIME.plusSeconds(1), cutoff.minusSeconds(1));
        UUID atCutoff = insertDeliveredEvent("resource:event-retention", BASE_TIME.plusSeconds(2), cutoff);
        UUID afterCutoff = insertDeliveredEvent(
                "resource:event-retention", BASE_TIME.plusSeconds(3), cutoff.plusSeconds(1));
        UUID pending = insertPendingEvent("resource:event-retention", BASE_TIME.minus(Duration.ofDays(90)), 3);

        assertThat(deliveryPersistence.purgeDeliveredEvents(cutoff, 1)).isEqualTo(1);
        assertThat(deliveryPersistence.purgeDeliveredEvents(cutoff, 1)).isEqualTo(1);
        assertThat(deliveryPersistence.purgeDeliveredEvents(cutoff, 1)).isZero();

        assertThat(jdbc.queryForList(
                        "SELECT event_id FROM watch_health_change_event ORDER BY event_id", UUID.class))
                .containsExactlyInAnyOrder(atCutoff, afterCutoff, pending)
                .doesNotContain(oldOne, oldTwo);
        EventDeliveryBacklogSnapshot retainedBacklog = deliveryPersistence.getBacklogSnapshot();
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
        synchronizeInactive(reference, 1, BASE_TIME);
        Instant cutoff = BASE_TIME.plus(Duration.ofDays(30));
        UUID locked = insertDeliveredEvent(reference, BASE_TIME, cutoff.minusSeconds(2));
        UUID available = insertDeliveredEvent(
                reference, BASE_TIME.plusSeconds(1), cutoff.minusSeconds(1));
        JdbcHealthChangeEventDeliveryAdapter competingAdapter = newDeliveryAdapter();

        assertThat(callWhileLocked(
                () -> assertThat(jdbc.queryForObject("""
                        SELECT event_id
                        FROM watch_health_change_event
                        WHERE event_id = ?
                        FOR UPDATE
                        """, UUID.class, locked)).isEqualTo(locked),
                () -> competingAdapter.purgeDeliveredEvents(cutoff, 1)))
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT event_id FROM watch_health_change_event ORDER BY event_id", UUID.class))
                .contains(locked)
                .doesNotContain(available);
    }

    private JdbcHealthChangeEventDeliveryAdapter newDeliveryAdapter() {
        return new JdbcHealthChangeEventDeliveryAdapter(
                JdbcClient.create(testDataSource), newTransactionOperations());
    }

    private UUID createDeliveryEvent(String reference) {
        synchronize(reference, 1, "https://" + reference.replace(':', '-') + ".example/path", BASE_TIME);
        ClaimedCheck check = claimOne();
        Instant changedAt = check.claimedAt();
        finalizeAt(check, changedAt);
        return jdbc.queryForObject("""
                SELECT event_id
                FROM watch_health_change_event
                WHERE resource_reference = ? AND changed_at = ?
                """, UUID.class, reference, databaseTime(changedAt));
    }

    private ClaimedHealthChangeEvent claimOneDelivery() {
        return deliveryPersistence.claimPendingEvent(LEASE).orElseThrow();
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

    private UUID lockLeadingDueEvent() {
        return jdbc.queryForObject("""
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
