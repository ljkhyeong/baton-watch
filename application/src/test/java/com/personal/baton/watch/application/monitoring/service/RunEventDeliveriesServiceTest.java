package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RunEventDeliveriesServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(10);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    @Test
    void claimsThenSendsThenFinalizesWithoutHoldingTheClaimOperation() {
        ClaimedHealthChangeEvent claim = claimed(1);
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence(claim);
        RunEventDeliveriesService service = service(
                persistence,
                payload -> {
                    assertEquals(claim.payload(), payload);
                    persistence.calls.add("send");
                    return EventDeliveryObservation.forHttpStatus(204, null);
                });

        EventDeliveryBatchResult result = service.runEventDeliveries();

        assertEquals(List.of("claim", "send", "finalize", "claim"), persistence.calls);
        assertEquals(
                new EventDeliveryBatchResult(1, 1, 0, 0, 0),
                result);
        assertEquals(LEASE, persistence.leaseDuration);
        assertEquals(claim.payload().eventId(), persistence.finalization.eventId());
        assertEquals(claim.leaseToken(), persistence.finalization.leaseToken());
        assertNull(persistence.finalization.nextAttemptAt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesInterruptionAndStopsClaimingMoreEvents(boolean interruptedBeforeStart) {
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence(claimed(1));
        RunEventDeliveriesService service = service(persistence, event -> {
            persistence.calls.add("send");
            Thread.currentThread().interrupt();
            return EventDeliveryObservation.internalFailure();
        });

        try {
            if (interruptedBeforeStart) {
                Thread.currentThread().interrupt();
            }

            EventDeliveryBatchResult result = service.runEventDeliveries();

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(interruptedBeforeStart ? List.of() : List.of("claim", "send", "finalize"), persistence.calls);
            int completed = interruptedBeforeStart ? 0 : 1;
            assertEquals(new EventDeliveryBatchResult(completed, 0, completed, 0, 0), result);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void schedulesRetryFromTheClaimAttemptAndConvertsUnexpectedSenderFailures() {
        RecordingEventDeliveryPersistence thirdAttempt = new RecordingEventDeliveryPersistence(claimed(3));
        service(thirdAttempt, event -> EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE))
                .runEventDeliveries();

        assertEquals(NOW.plusSeconds(40), thirdAttempt.finalization.nextAttemptAt());
        assertEquals(EventDeliveryOutcome.DNS_FAILURE, thirdAttempt.finalization.observation().outcome());

        RecordingEventDeliveryPersistence unexpectedFailure = new RecordingEventDeliveryPersistence(claimed(1));
        EventDeliveryBatchResult result = service(unexpectedFailure, event -> {
                    throw new IllegalStateException("sensitive transport detail");
                })
                .runEventDeliveries();

        assertEquals(EventDeliveryObservation.internalFailure(), unexpectedFailure.finalization.observation());
        assertEquals(1, result.retryScheduled());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void persistsServerRetryTimeWithoutChangingTheClaimedEvent(int httpStatus) {
        ClaimedHealthChangeEvent claim = claimed(1);
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence(claim);

        EventDeliveryBatchResult result = service(persistence,
                event -> EventDeliveryObservation.forHttpStatus(httpStatus, NOW.plusSeconds(45)))
                .runEventDeliveries();

        assertEquals(1, result.retryScheduled());
        assertEquals(NOW.plusSeconds(45), persistence.finalization.nextAttemptAt());
        assertEquals(claim.payload().eventId(), persistence.finalization.eventId());
        assertEquals(claim.leaseToken(), persistence.finalization.leaseToken());
        assertEquals(httpStatus, persistence.finalization.observation().httpStatusCode());
    }

    @ParameterizedTest
    @EnumSource(
            value = EventDeliveryFinalizationStatus.class,
            names = {"ALREADY_DELIVERED", "STALE_CLAIM"})
    void reportsIdempotentAndStaleFinalizationsSeparatelyFromTransportOutcome(EventDeliveryFinalizationStatus status) {
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence(claimed(1));
        persistence.finalizationStatus = status;

        EventDeliveryBatchResult result = service(
                        persistence, event -> EventDeliveryObservation.failure(EventDeliveryOutcome.CONNECT_TIMEOUT))
                .runEventDeliveries();

        assertEquals(status == EventDeliveryFinalizationStatus.ALREADY_DELIVERED ? 1 : 0, result.alreadyDelivered());
        assertEquals(status == EventDeliveryFinalizationStatus.STALE_CLAIM ? 1 : 0, result.staleClaims());
        assertEquals(0, result.retryScheduled());
        assertEquals(EventDeliveryOutcome.CONNECT_TIMEOUT, persistence.finalization.observation().outcome());
    }

    @Test
    void usesTheDatabaseClaimTimeAsTheCompletionFloor() {
        Instant databaseClaimedAt = NOW.plusSeconds(5);
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence(
                claimed(1, databaseClaimedAt));

        service(persistence, event -> EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE))
                .runEventDeliveries();

        assertEquals(databaseClaimedAt, persistence.finalization.completedAt());
        assertEquals(databaseClaimedAt.plus(INITIAL_BACKOFF), persistence.finalization.nextAttemptAt());
    }

    private RunEventDeliveriesService service(
            RecordingEventDeliveryPersistence persistence, HealthChangeEventSender sender) {
        return new RunEventDeliveriesService(
                persistence,
                sender,
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                new EventDeliveryRetryPolicy(INITIAL_BACKOFF, MAX_BACKOFF),
                5);
    }

    private ClaimedHealthChangeEvent claimed(int deliveryAttempt) {
        return claimed(deliveryAttempt, NOW);
    }

    private ClaimedHealthChangeEvent claimed(int deliveryAttempt, Instant claimedAt) {
        return new ClaimedHealthChangeEvent(
                new HealthChangeEventPayload(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        new ResourceReference("resource-1"),
                        new SourceRevision(7),
                        Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                        Health.UNKNOWN,
                        Health.HEALTHY,
                        NOW.minusSeconds(1)),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                deliveryAttempt,
                claimedAt,
                false);
    }
}
