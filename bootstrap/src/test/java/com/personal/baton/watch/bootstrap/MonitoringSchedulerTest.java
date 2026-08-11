package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MonitoringSchedulerTest {

    @Test
    void propagatesCheckFailuresToTheScheduledObservationBoundary() {
        IllegalStateException failure = new IllegalStateException("sensitive check detail");
        MonitoringScheduler scheduler = new MonitoringScheduler(
                () -> {
                    throw failure;
                },
                () -> 0,
                () -> 0,
                new MonitoringMetrics(new SimpleMeterRegistry()));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                scheduler::checkDueMonitors);

        assertSame(failure, thrown);
    }

    @Test
    void staleProjectionFailuresDoNotBlockAttemptRetention() {
        IllegalStateException failure = new IllegalStateException("sensitive maintenance detail");
        AtomicBoolean retentionInvoked = new AtomicBoolean();
        MonitoringScheduler scheduler = new MonitoringScheduler(
                () -> null,
                () -> {
                    throw failure;
                },
                () -> {
                    retentionInvoked.set(true);
                    return 0;
                },
                new MonitoringMetrics(new SimpleMeterRegistry()));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                scheduler::markStaleProjections);
        scheduler.purgeAttemptHistory();

        assertSame(failure, thrown);
        assertTrue(retentionInvoked.get());
    }

    @Test
    void attemptRetentionFailuresDoNotBlockStaleProjectionMaintenance() {
        IllegalStateException failure = new IllegalStateException("sensitive retention detail");
        AtomicBoolean staleMaintenanceInvoked = new AtomicBoolean();
        MonitoringScheduler scheduler = new MonitoringScheduler(
                () -> null,
                () -> {
                    staleMaintenanceInvoked.set(true);
                    return 0;
                },
                () -> {
                    throw failure;
                },
                new MonitoringMetrics(new SimpleMeterRegistry()));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                scheduler::purgeAttemptHistory);
        scheduler.markStaleProjections();

        assertSame(failure, thrown);
        assertTrue(staleMaintenanceInvoked.get());
    }
}
