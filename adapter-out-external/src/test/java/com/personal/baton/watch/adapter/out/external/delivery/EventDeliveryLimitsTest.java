package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventDeliveryLimitsTest {

    @Test
    void acceptsStrictPositiveBoundedLimits() {
        assertDoesNotThrow(() -> new EventDeliveryLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                8_192,
                100,
                8_192));
    }

    @Test
    void rejectsDisabledOrInvalidResourceLimits() {
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                1,
                1));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                0,
                1,
                1));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                0,
                1));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                1,
                0));
    }
}
