package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeBoundaryPolicyTest {

    private static final Instant BASE = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void acceptsTheSupportedOffsetCeilingForBothDirections() {
        assertEquals(
                BASE.plus(TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET),
                TimeBoundaryPolicy.add(
                        BASE, TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET, "offset"));
        assertEquals(
                BASE.minus(TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET),
                TimeBoundaryPolicy.subtract(
                        BASE, TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET, "offset"));
    }

    @Test
    void rejectsOffsetsAboveTheSingleHardCeiling() {
        Duration unsupported = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET.plusNanos(1);

        assertThrows(IllegalArgumentException.class, () ->
                TimeBoundaryPolicy.requireSupportedOffset(unsupported, "offset"));
    }
}
