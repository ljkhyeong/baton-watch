package com.personal.baton.watch.domain.monitoring;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MonitorProjectionTest {

    @Test
    void rejectsNextCheckTimeForInactiveMonitor() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MonitorProjection(
                        new ResourceReference("resource:inactive"),
                        new SourceRevision(1),
                        MonitoringState.INACTIVE,
                        new HealthDerivation(Health.UNKNOWN, 0),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(Instant.parse("2026-08-01T00:00:00Z"))));
    }
}
