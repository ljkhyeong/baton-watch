package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeBoundaryPolicyTest {

    @Test
    void acceptsTheSupportedOffsetCeiling() {
        assertEquals(
                TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET,
                TimeBoundaryPolicy.requireSupportedOffset(
                        TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET, "offset"));
    }

    @Test
    void rejectsOffsetsAboveTheSingleHardCeiling() {
        Duration unsupported = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET.plusNanos(1);

        assertThrows(IllegalArgumentException.class, () ->
                TimeBoundaryPolicy.requireSupportedOffset(unsupported, "offset"));
    }
}
