package com.personal.baton.watch.application.monitoring.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Instant;
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
                () -> new EventDeliveryObservation(EventDeliveryOutcome.DELIVERED, 199));
        assertThrows(
                IllegalArgumentException.class,
                () -> EventDeliveryObservation.failure(EventDeliveryOutcome.DELIVERED));
    }

    @Test
    void separatesImmutablePayloadInvariantsFromDeliveryClaimInvariants() {
        assertThrows(IllegalArgumentException.class, () -> payload(Health.HEALTHY, Health.HEALTHY));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClaimedHealthChangeEvent(
                        payload(Health.UNKNOWN, Health.HEALTHY),
                        UUID.randomUUID(),
                        0,
                        Instant.now(),
                        false));
    }

    private HealthChangeEventPayload payload(Health previous, Health current) {
        return new HealthChangeEventPayload(
                UUID.randomUUID(),
                new ResourceReference("resource-1"),
                new SourceRevision(1),
                Optional.empty(),
                previous,
                current,
                Instant.parse("2026-08-01T00:00:00Z"));
    }
}
