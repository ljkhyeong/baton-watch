package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunEventDeliveriesServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(10);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    @Test
    void claimsThenSendsThenFinalizesWithoutHoldingTheClaimOperation() {
        List<String> calls = new ArrayList<>();
        ClaimedHealthChangeEvent claim = claimed(1);
        RecordingPersistence persistence = new RecordingPersistence(calls, claim);
        RunEventDeliveriesService service = service(
                persistence,
                payload -> {
                    assertEquals(claim.payload(), payload);
                    calls.add("send");
                    return EventDeliveryObservation.forHttpStatus(204);
                });

        EventDeliveryBatchResult result = service.runEventDeliveries();

        assertEquals(List.of("claim", "send", "finalize", "claim"), calls);
        assertEquals(
                new EventDeliveryBatchResult(1, 1, 0, 0, 0),
                result);
        assertEquals(LEASE, persistence.leaseDuration);
        assertEquals(claim.payload().eventId(), persistence.finalization.eventId());
        assertEquals(claim.leaseToken(), persistence.finalization.leaseToken());
        assertNull(persistence.finalization.nextAttemptAt());
    }

    @Test
    void schedulesRetryFromTheClaimAttemptAndConvertsUnexpectedSenderFailures() {
        RecordingPersistence thirdAttempt = new RecordingPersistence(new ArrayList<>(), claimed(3));
        service(thirdAttempt, event -> EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE))
                .runEventDeliveries();

        assertEquals(NOW.plusSeconds(40), thirdAttempt.finalization.nextAttemptAt());
        assertEquals(EventDeliveryOutcome.DNS_FAILURE, thirdAttempt.finalization.observation().outcome());

        RecordingPersistence unexpectedFailure = new RecordingPersistence(new ArrayList<>(), claimed(1));
        EventDeliveryBatchResult result = service(unexpectedFailure, event -> {
                    throw new IllegalStateException("sensitive transport detail");
                })
                .runEventDeliveries();

        assertEquals(EventDeliveryObservation.internalFailure(), unexpectedFailure.finalization.observation());
        assertEquals(1, result.retryScheduled());
    }

    @Test
    void reportsIdempotentAndStaleFinalizationsSeparatelyFromTransportOutcome() {
        RecordingPersistence persistence = new RecordingPersistence(new ArrayList<>(), claimed(1));
        persistence.status = EventDeliveryFinalizationStatus.ALREADY_DELIVERED;

        EventDeliveryBatchResult result = service(
                        persistence, event -> EventDeliveryObservation.failure(EventDeliveryOutcome.CONNECT_TIMEOUT))
                .runEventDeliveries();

        assertEquals(1, result.alreadyDelivered());
        assertEquals(0, result.retryScheduled());
        assertEquals(EventDeliveryOutcome.CONNECT_TIMEOUT, persistence.finalization.observation().outcome());

        RecordingPersistence stalePersistence = new RecordingPersistence(new ArrayList<>(), claimed(1));
        stalePersistence.status = EventDeliveryFinalizationStatus.STALE_CLAIM;

        EventDeliveryBatchResult staleResult = service(
                        stalePersistence,
                        event -> EventDeliveryObservation.failure(EventDeliveryOutcome.CONNECT_TIMEOUT))
                .runEventDeliveries();

        assertEquals(1, staleResult.staleClaims());
        assertEquals(0, staleResult.retryScheduled());
    }

    @Test
    void usesTheDatabaseClaimTimeAsTheCompletionFloor() {
        Instant databaseClaimedAt = NOW.plusSeconds(5);
        RecordingPersistence persistence = new RecordingPersistence(
                new ArrayList<>(), claimed(1, databaseClaimedAt));

        service(persistence, event -> EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE))
                .runEventDeliveries();

        assertEquals(databaseClaimedAt, persistence.finalization.completedAt());
        assertEquals(databaseClaimedAt.plus(INITIAL_BACKOFF), persistence.finalization.nextAttemptAt());
    }

    private RunEventDeliveriesService service(
            RecordingPersistence persistence,
            com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender sender) {
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

    private static final class RecordingPersistence implements HealthChangeEventDeliveryPersistencePort {

        private final List<String> calls;
        private final ClaimedHealthChangeEvent claimed;
        private Duration leaseDuration;
        private EventDeliveryFinalization finalization;
        private EventDeliveryFinalizationStatus status = EventDeliveryFinalizationStatus.APPLIED;
        private boolean claimReturned;

        private RecordingPersistence(List<String> calls, ClaimedHealthChangeEvent claimed) {
            this.calls = calls;
            this.claimed = claimed;
        }

        @Override
        public Optional<ClaimedHealthChangeEvent> claimPendingEvent(Duration leaseDuration) {
            calls.add("claim");
            this.leaseDuration = leaseDuration;
            if (claimReturned) {
                return Optional.empty();
            }
            claimReturned = true;
            return Optional.of(claimed);
        }

        @Override
        public EventDeliveryFinalizationStatus finalizeDelivery(EventDeliveryFinalization finalization) {
            calls.add("finalize");
            this.finalization = finalization;
            return status;
        }

        @Override
        public int purgeDeliveredEvents(Instant deliveredBefore, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventDeliveryBacklogSnapshot getBacklogSnapshot() {
            throw new UnsupportedOperationException();
        }
    }
}
