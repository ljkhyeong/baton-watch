package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitoringMaintenanceServicesTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Test
    void staleSweepAndRetentionUseTheirOwnFixedClockCutoffsAndBatchBound() {
        RecordingMonitorPersistence monitors = new RecordingMonitorPersistence();
        RecordingCheckWorkPersistence checkWork = new RecordingCheckWorkPersistence();
        checkWork.purgedAttempts = 3;
        MonitoringMaintenanceService service = new MonitoringMaintenanceService(
                monitors,
                checkWork,
                () -> NOW,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                25);

        assertEquals(2, service.markStaleProjectionsUnknown());
        assertEquals(NOW.minus(Duration.ofMinutes(10)), monitors.staleBefore);
        assertEquals(NOW, monitors.markedAt);
        assertEquals(25, monitors.limit);

        assertEquals(3, service.purgeAttemptHistory());
        assertEquals(NOW.minus(Duration.ofDays(30)), checkWork.completedBefore);
        assertEquals(25, checkWork.limit);
    }

    @Test
    void calculatesSignedDatabaseOffsetFromTheLocalMeasurementMidpoint() {
        Instant before = Instant.parse("2026-08-01T00:00:00Z");
        Instant after = before.plusSeconds(4);

        assertEquals(
                Duration.ofSeconds(1),
                MonitoringMaintenanceService.clockOffset(before, before.plusSeconds(1), after));
        assertEquals(
                Duration.ofSeconds(-1),
                MonitoringMaintenanceService.clockOffset(before, before.plusSeconds(3), after));
    }

    private static final class RecordingMonitorPersistence implements MonitorPersistencePort {

        @Override
        public MonitorCheckRequestResult requestCheck(
                ResourceReference reference, Instant requestedAt, Duration minimumInterval) {
            throw new UnsupportedOperationException();
        }

        private Instant staleBefore;
        private Instant markedAt;
        private int limit;

        @Override
        public SynchronizationResult synchronize(SynchronizeMonitorCommand command, Instant synchronizedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<MonitorProjection> findProjections(List<ResourceReference> references) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markStaleUnknown(Instant staleBefore, Instant markedAt, int limit) {
            this.staleBefore = staleBefore;
            this.markedAt = markedAt;
            this.limit = limit;
            return 2;
        }
    }
}
