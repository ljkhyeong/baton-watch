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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventDeliveryApplicationModelTest {

    @ParameterizedTest
    @CsvSource({
        "200, DELIVERED",
        "299, DELIVERED",
        "300, HTTP_CLIENT_ERROR",
        "399, HTTP_CLIENT_ERROR",
        "400, HTTP_CLIENT_ERROR",
        "499, HTTP_CLIENT_ERROR",
        "500, HTTP_SERVER_ERROR",
        "599, HTTP_SERVER_ERROR"
    })
    void mapsFinalHttpStatusBoundaries(int status, EventDeliveryOutcome expected) {
        assertEquals(expected, EventDeliveryObservation.forHttpStatus(status).outcome());
    }

    @Test
    void rejectsUnsupportedStatusesAndMismatchedOutcomes() {
        assertThrows(IllegalArgumentException.class, () -> EventDeliveryObservation.forHttpStatus(199));
        assertThrows(IllegalArgumentException.class, () -> EventDeliveryObservation.forHttpStatus(600));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EventDeliveryObservation(EventDeliveryOutcome.DELIVERED, 199));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EventDeliveryObservation(EventDeliveryOutcome.DELIVERED, 302));
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
