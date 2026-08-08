package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CheckerLimitsTest {

    @Test
    void enforcesResponseAndHeaderHardCeilings() {
        assertDoesNotThrow(() -> limits(
                OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES,
                OutboundResourceBounds.MAX_HEADER_COUNT,
                OutboundResourceBounds.MAX_HEADER_LINE_LENGTH));
        assertThrows(IllegalArgumentException.class, () -> limits(
                OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES + 1,
                100,
                8_192));
        assertThrows(IllegalArgumentException.class, () -> limits(
                64 * 1024,
                OutboundResourceBounds.MAX_HEADER_COUNT + 1,
                8_192));
        assertThrows(IllegalArgumentException.class, () -> limits(
                64 * 1024,
                100,
                OutboundResourceBounds.MAX_HEADER_LINE_LENGTH + 1));
    }

    private static CheckerLimits limits(
            long maxResponseBytes, int maxHeaderCount, int maxHeaderLineLength) {
        return new CheckerLimits(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                maxResponseBytes,
                3,
                maxHeaderCount,
                maxHeaderLineLength);
    }
}
