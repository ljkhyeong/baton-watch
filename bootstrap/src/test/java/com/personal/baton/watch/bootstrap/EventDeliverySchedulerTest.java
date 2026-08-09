package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EventDeliverySchedulerTest {

    @Test
    void recordsDeliveryRetentionAndBacklogWithoutExposingIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);
        EventDeliveryScheduler scheduler = new EventDeliveryScheduler(
                () -> new EventDeliveryBatchResult(
                        2,
                        1,
                        1,
                        0,
                        0,
                        Map.of(
                                EventDeliveryOutcome.DELIVERED, 1,
                                EventDeliveryOutcome.READ_TIMEOUT, 1)),
                metrics);
        EventDeliveryMaintenanceScheduler maintenance = new EventDeliveryMaintenanceScheduler(
                () -> 3,
                () -> new EventDeliveryBacklog(4, Optional.of(Duration.ofSeconds(73))),
                metrics);

        scheduler.deliverPendingEvents();
        maintenance.purgeDeliveredEventHistory();
        maintenance.refreshEventDeliveryBacklog();

        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.finalizations")
                        .tag("status", "retry_scheduled")
                        .counter()
                        .count());
        assertEquals(
                3.0,
                registry.get("baton.watch.maintenance.items")
                        .tag("operation", "delivered_event_purged")
                        .counter()
                        .count());
        assertEquals(4.0, registry.get("baton.watch.event.delivery.backlog").gauge().value());
        assertEquals(73.0, registry.get("baton.watch.event.delivery.oldest.age").gauge().value());
    }

    @Test
    void propagatesWorkerFailuresToTheScheduledObservationBoundary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);
        IllegalStateException failure = new IllegalStateException("sensitive callback detail");
        EventDeliveryScheduler scheduler = new EventDeliveryScheduler(
                () -> {
                    throw failure;
                },
                metrics);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                scheduler::deliverPendingEvents);

        assertSame(failure, thrown);
    }

    @Test
    void keepsBacklogRefreshIndependentFromRetentionFailures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);
        IllegalStateException failure = new IllegalStateException("sensitive retention detail");
        EventDeliveryMaintenanceScheduler maintenance = new EventDeliveryMaintenanceScheduler(
                () -> {
                    throw failure;
                },
                () -> new EventDeliveryBacklog(4, Optional.of(Duration.ofSeconds(73))),
                metrics);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                maintenance::purgeDeliveredEventHistory);
        maintenance.refreshEventDeliveryBacklog();

        assertSame(failure, thrown);
        assertEquals(4.0, registry.get("baton.watch.event.delivery.backlog").gauge().value());
        assertEquals(73.0, registry.get("baton.watch.event.delivery.oldest.age").gauge().value());
    }

    @Test
    void propagatesBacklogRefreshFailuresToTheScheduledObservationBoundary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IllegalStateException failure = new IllegalStateException("sensitive backlog detail");
        EventDeliveryMaintenanceScheduler maintenance = new EventDeliveryMaintenanceScheduler(
                () -> 0,
                () -> {
                    throw failure;
                },
                new MonitoringMetrics(registry));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                maintenance::refreshEventDeliveryBacklog);

        assertSame(failure, thrown);
    }
}
