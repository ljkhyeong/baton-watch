package com.personal.baton.watch.application.monitoring.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventDeliveryApplicationModelTest {

    @Test
    void mapsOnlyTwoHundredsToDeliveredAndBoundsOtherHttpOutcomes() {
        assertEquals(EventDeliveryOutcome.DELIVERED, EventDeliveryObservation.forHttpStatus(204).outcome());
        assertEquals(
                EventDeliveryOutcome.HTTP_CLIENT_ERROR,
                EventDeliveryObservation.forHttpStatus(302).outcome());
        assertEquals(
                EventDeliveryOutcome.HTTP_CLIENT_ERROR,
                EventDeliveryObservation.forHttpStatus(429).outcome());
        assertEquals(
                EventDeliveryOutcome.HTTP_SERVER_ERROR,
                EventDeliveryObservation.forHttpStatus(503).outcome());
        assertThrows(IllegalArgumentException.class, () -> EventDeliveryObservation.forHttpStatus(199));
        assertThrows(
                IllegalArgumentException.class,
                () -> EventDeliveryObservation.failure(EventDeliveryOutcome.DELIVERED));
    }

    @Test
    void claimedEventRequiresARealChangeAndPositiveAttempt() {
        assertThrows(IllegalArgumentException.class, () -> claimed(Health.HEALTHY, Health.HEALTHY, 1));
        assertThrows(IllegalArgumentException.class, () -> claimed(Health.UNKNOWN, Health.HEALTHY, 0));

        assertTrue(claimed(Health.UNKNOWN, Health.HEALTHY, 1).attemptId().isEmpty());
    }

    @Test
    void batchResultDefensivelyCopiesBoundedOutcomeCounts() {
        Map<EventDeliveryOutcome, Integer> mutable = new java.util.EnumMap<>(EventDeliveryOutcome.class);
        mutable.put(EventDeliveryOutcome.DELIVERED, 1);
        EventDeliveryBatchResult result = new EventDeliveryBatchResult(1, 1, 0, 0, 0, mutable);

        mutable.clear();

        assertEquals(1, result.outcomes().get(EventDeliveryOutcome.DELIVERED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EventDeliveryBatchResult(1, 1, 0, 0, 0, Map.of()));
    }

    private ClaimedHealthChangeEvent claimed(Health previous, Health current, int deliveryAttempt) {
        return new ClaimedHealthChangeEvent(
                UUID.randomUUID(),
                new ResourceReference("resource-1"),
                new SourceRevision(1),
                Optional.empty(),
                previous,
                current,
                Instant.parse("2026-08-01T00:00:00Z"),
                UUID.randomUUID(),
                deliveryAttempt);
    }
}
