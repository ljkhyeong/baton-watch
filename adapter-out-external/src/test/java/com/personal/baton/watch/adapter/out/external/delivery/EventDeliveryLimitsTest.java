package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventDeliveryLimitsTest {

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
