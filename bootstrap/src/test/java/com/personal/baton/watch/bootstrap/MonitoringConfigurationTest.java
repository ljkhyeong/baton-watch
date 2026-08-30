package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.watch.application.monitoring.port.in.GetDatabaseClockOffsetUseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MonitoringConfigurationTest {

    @Test
    void calculatesSignedDatabaseOffsetFromTheLocalMeasurementMidpoint() {
        Instant before = Instant.parse("2026-08-01T00:00:00Z");
        Instant after = before.plusSeconds(4);

        assertOffset(Duration.ofSeconds(1), before.plusSeconds(1), before, after);
        assertOffset(Duration.ofSeconds(-1), before.plusSeconds(3), before, after);
    }

    private static void assertOffset(
            Duration expected,
            Instant databaseTime,
            Instant before,
            Instant after) {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(before, after);
        GetDatabaseClockOffsetUseCase useCase = new MonitoringConfiguration()
                .getDatabaseClockOffsetUseCase(() -> databaseTime, clock);

        assertEquals(expected, useCase.getDatabaseClockOffset());
    }

}
