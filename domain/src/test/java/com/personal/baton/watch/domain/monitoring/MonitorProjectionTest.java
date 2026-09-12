package com.personal.baton.watch.domain.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MonitorProjectionTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @ParameterizedTest
    @CsvSource({
        "INACTIVE, , , INACTIVE",
        "ACTIVE, 30, , SCHEDULED",
        "ACTIVE, 0, , QUEUED",
        "ACTIVE, -30, , QUEUED",
        "ACTIVE, -30, 30, IN_PROGRESS",
        "ACTIVE, 30, 30, IN_PROGRESS",
        "ACTIVE, -30, 0, QUEUED",
        "ACTIVE, -30, -1, QUEUED",
        "ACTIVE, 30, 0, SCHEDULED"
    })
    void derivesCheckStatusAtScheduleAndLeaseBoundaries(
            MonitoringState state, Long nextOffset, Long leaseOffset, CheckStatus expected) {
        assertEquals(expected, projection(state, nextOffset, leaseOffset).checkStatusAt(NOW));
    }

    @Test
    void rejectsMissingNextCheckTimeForActiveMonitor() {
        assertThrows(IllegalArgumentException.class, () -> projection(MonitoringState.ACTIVE, null, null));
    }

    @Test
    void rejectsNextCheckTimeForInactiveMonitor() {
        assertThrows(IllegalArgumentException.class, () -> projection(MonitoringState.INACTIVE, 0L, null));
    }

    @Test
    void rejectsLeaseForInactiveMonitor() {
        assertThrows(IllegalArgumentException.class, () -> projection(MonitoringState.INACTIVE, null, 30L));
    }

    private static MonitorProjection projection(MonitoringState state, Long nextOffset, Long leaseOffset) {
        return new MonitorProjection(
                new ResourceReference("resource:progress"),
                new SourceRevision(1),
                state,
                new HealthDerivation(Health.UNKNOWN, 0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(nextOffset).map(NOW::plusSeconds),
                Optional.ofNullable(leaseOffset).map(NOW::plusSeconds));
    }
}
