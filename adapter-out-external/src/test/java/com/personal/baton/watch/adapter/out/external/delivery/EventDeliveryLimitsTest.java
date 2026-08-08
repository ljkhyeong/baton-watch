package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
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
    void rejectsDisabledOrIncoherentLimits() {
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                1,
                1));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ofSeconds(2),
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

    @Test
    void rejectsResponseAndHeaderValuesAboveTheirHardCeilings() {
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES + 1,
                100,
                8_192));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                8_192,
                OutboundResourceBounds.MAX_HEADER_COUNT + 1,
                8_192));
        assertThrows(IllegalArgumentException.class, () -> new EventDeliveryLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                8_192,
                100,
                OutboundResourceBounds.MAX_HEADER_LINE_LENGTH + 1));
    }
}
