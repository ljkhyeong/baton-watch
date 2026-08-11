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
                    return EventDeliveryObservation.delivered(204);
                });

        EventDeliveryBatchResult result = service.runEventDeliveries();

        assertEquals(List.of("claim", "send", "finalize"), calls);
        assertEquals(
                new EventDeliveryBatchResult(1, 1, 0, 0, 0),
                result);
        assertEquals(NOW, persistence.claimedAt);
        assertEquals(NOW.plus(LEASE), persistence.leaseUntil);
        assertEquals(5, persistence.limit);
        assertEquals(claim.payload().eventId(), persistence.finalization.eventId());
        assertEquals(claim.leaseToken(), persistence.finalization.leaseToken());
        assertEquals(claim.deliveryAttempt(), persistence.finalization.deliveryAttempt());
        assertNull(persistence.finalization.nextAttemptAt());
    }

    @Test
    void schedulesBoundedExponentialBackoffFromTheClaimAttempt() {
        RecordingPersistence thirdAttempt = new RecordingPersistence(new ArrayList<>(), claimed(3));
        service(thirdAttempt, event -> EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE))
                .runEventDeliveries();

        assertEquals(NOW.plusSeconds(40), thirdAttempt.finalization.nextAttemptAt());
        assertEquals(EventDeliveryOutcome.DNS_FAILURE, thirdAttempt.finalization.observation().outcome());

        RecordingPersistence capped = new RecordingPersistence(new ArrayList<>(), claimed(Integer.MAX_VALUE));
        EventDeliveryBatchResult result = service(capped, event -> {
                    throw new IllegalStateException("sensitive transport detail");
                })
                .runEventDeliveries();

        assertEquals(NOW.plus(MAX_BACKOFF), capped.finalization.nextAttemptAt());
        assertEquals(EventDeliveryObservation.internalFailure(), capped.finalization.observation());
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
                deliveryAttempt);
    }

    private static final class RecordingPersistence implements HealthChangeEventDeliveryPersistencePort {

        private final List<String> calls;
        private final ClaimedHealthChangeEvent claimed;
        private Instant claimedAt;
        private Instant leaseUntil;
        private int limit;
        private EventDeliveryFinalization finalization;
        private EventDeliveryFinalizationStatus status = EventDeliveryFinalizationStatus.APPLIED;

        private RecordingPersistence(List<String> calls, ClaimedHealthChangeEvent claimed) {
            this.calls = calls;
            this.claimed = claimed;
        }

        @Override
        public List<ClaimedHealthChangeEvent> claimPendingEvents(Instant claimedAt, Instant leaseUntil, int limit) {
            calls.add("claim");
            this.claimedAt = claimedAt;
            this.leaseUntil = leaseUntil;
            this.limit = limit;
            return List.of(claimed);
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
