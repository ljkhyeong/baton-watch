package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.personal.baton.watch.application.monitoring.port.in.GetDatabaseClockOffsetUseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
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
        GetDatabaseClockOffsetUseCase useCase = new MonitoringConfiguration()
                .getDatabaseClockOffsetUseCase(() -> databaseTime, new SequenceClock(before, after));

        assertEquals(expected, useCase.getDatabaseClockOffset());
    }

    private static final class SequenceClock extends Clock {

        private final Queue<Instant> instants;

        private SequenceClock(Instant... instants) {
            this.instants = new ArrayDeque<>(Arrays.asList(instants));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instants.remove();
        }
    }
}
