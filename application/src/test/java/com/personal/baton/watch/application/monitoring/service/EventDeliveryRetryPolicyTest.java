package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventDeliveryRetryPolicyTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void acceptsTheHardCeilingAndRejectsLargerDelays() {
        new EventDeliveryRetryPolicy(
                Duration.ofSeconds(5),
                TimeBoundaryPolicy.MAX_EVENT_DELIVERY_RETRY_DELAY);
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryRetryPolicy(
                Duration.ofSeconds(5),
                TimeBoundaryPolicy.MAX_EVENT_DELIVERY_RETRY_DELAY.plusNanos(1)));
    }

    @Test
    void calculatesExponentialBackoffAndCapsLargeAttemptCounts() {
        EventDeliveryRetryPolicy policy = new EventDeliveryRetryPolicy(
                Duration.ofSeconds(10), Duration.ofSeconds(60));

        assertEquals(COMPLETED_AT.plusSeconds(10), policy.nextAttemptAt(COMPLETED_AT, 1, null));
        assertEquals(COMPLETED_AT.plusSeconds(40), policy.nextAttemptAt(COMPLETED_AT, 3, null));
        assertEquals(COMPLETED_AT.plusSeconds(60), policy.nextAttemptAt(COMPLETED_AT, Integer.MAX_VALUE, null));
    }

    @ParameterizedTest
    @CsvSource({"-60, 10", "0, 10", "5, 10", "45, 45", "60, 60", "3600, 60"})
    void combinesServerRetryTimeWithBackoffAndMaximum(long serverDelay, long expectedDelay) {
        EventDeliveryRetryPolicy policy = new EventDeliveryRetryPolicy(
                Duration.ofSeconds(10), Duration.ofSeconds(60));

        assertEquals(COMPLETED_AT.plusSeconds(expectedDelay), policy.nextAttemptAt(
                COMPLETED_AT, 1, COMPLETED_AT.plusSeconds(serverDelay)));
    }

    @Test
    void rejectsInvalidDelayRelationships() {
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryRetryPolicy(
                Duration.ZERO, Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryRetryPolicy(
                Duration.ofSeconds(11), Duration.ofSeconds(10)));
    }
}
