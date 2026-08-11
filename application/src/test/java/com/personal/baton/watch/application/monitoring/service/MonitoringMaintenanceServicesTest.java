package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MonitoringMaintenanceServicesTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Test
    void staleSweepUsesFixedClockThresholdAndBatchBound() {
        RecordingMonitorPersistence persistence = new RecordingMonitorPersistence();
        MarkStaleProjectionsService service = new MarkStaleProjectionsService(
                persistence, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), 25);

        int changed = service.markStaleProjectionsUnknown();

        assertEquals(2, changed);
        assertEquals(NOW.minus(Duration.ofMinutes(10)), persistence.staleBefore);
        assertEquals(NOW, persistence.markedAt);
        assertEquals(25, persistence.limit);
    }

    @Test
    void retentionCleanupUsesFixedClockCutoffAndBatchBound() {
        RecordingWorkPersistence persistence = new RecordingWorkPersistence();
        PurgeAttemptHistoryService service = new PurgeAttemptHistoryService(
                persistence, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30), 100);

        int purged = service.purgeAttemptHistory();

        assertEquals(3, purged);
        assertEquals(NOW.minus(Duration.ofDays(30)), persistence.completedBefore);
        assertEquals(100, persistence.limit);
    }

    private static final class RecordingMonitorPersistence implements MonitorPersistencePort {

        private Instant staleBefore;
        private Instant markedAt;
        private int limit;

        @Override
        public SynchronizationResult synchronize(SynchronizeMonitorCommand command, Instant synchronizedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MonitorProjection> findProjection(ResourceReference resourceReference) {
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

    private static final class RecordingWorkPersistence implements CheckWorkPersistencePort {

        private Instant completedBefore;
        private int limit;

        @Override
        public List<ClaimedCheck> claimDueChecks(Instant claimedAt, Instant leaseUntil, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeAttempts(Instant completedBefore, int limit) {
            this.completedBefore = completedBefore;
            this.limit = limit;
            return 3;
        }
    }
}
