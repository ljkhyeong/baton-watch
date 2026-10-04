package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
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
        assertThrows(IllegalArgumentException.class, () -> withResponseBytes(0));
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

    @Test
    void appliesTheInclusiveResponseByteCeiling() {
        long ceiling = OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES;

        assertEquals(ceiling, withResponseBytes(ceiling).maxResponseBytes());
        assertThrows(IllegalArgumentException.class, () -> withResponseBytes(ceiling + 1));
    }

    private static EventDeliveryLimits withResponseBytes(long maxResponseBytes) {
        return new EventDeliveryLimits(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), maxResponseBytes, 1, 1);
    }
}
