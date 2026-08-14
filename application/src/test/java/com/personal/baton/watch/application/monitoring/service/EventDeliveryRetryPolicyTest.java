package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventDeliveryRetryPolicyTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void acceptsTheHardCeilingAndRejectsLargerDelays() {
        assertDoesNotThrow(() -> new EventDeliveryRetryPolicy(
                Duration.ofSeconds(5),
                TimeBoundaryPolicy.MAX_EVENT_DELIVERY_RETRY_DELAY));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryRetryPolicy(
                Duration.ofSeconds(5),
                TimeBoundaryPolicy.MAX_EVENT_DELIVERY_RETRY_DELAY.plusNanos(1)));
    }

    @Test
    void calculatesExponentialBackoffAndCapsLargeAttemptCounts() {
        EventDeliveryRetryPolicy policy = new EventDeliveryRetryPolicy(
                Duration.ofSeconds(10), Duration.ofSeconds(60));

        assertEquals(COMPLETED_AT.plusSeconds(10), policy.nextAttemptAt(COMPLETED_AT, 1));
        assertEquals(COMPLETED_AT.plusSeconds(40), policy.nextAttemptAt(COMPLETED_AT, 3));
        assertEquals(COMPLETED_AT.plusSeconds(60), policy.nextAttemptAt(COMPLETED_AT, Integer.MAX_VALUE));
    }

    @Test
    void rejectsInvalidDelayRelationshipsAndAttemptCounts() {
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryRetryPolicy(
                Duration.ZERO, Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryRetryPolicy(
                Duration.ofSeconds(11), Duration.ofSeconds(10)));

        EventDeliveryRetryPolicy policy = new EventDeliveryRetryPolicy(
                Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> policy.nextAttemptAt(COMPLETED_AT, 0));
    }
}
