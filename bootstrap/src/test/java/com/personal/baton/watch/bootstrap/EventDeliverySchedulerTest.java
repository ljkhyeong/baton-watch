package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        maintenance.maintainDeliveryState();

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
    void containsWorkerFailuresAndStillRefreshesBacklog() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);
        EventDeliveryScheduler scheduler = new EventDeliveryScheduler(
                () -> {
                    throw new IllegalStateException("sensitive callback detail");
                },
                metrics);
        EventDeliveryMaintenanceScheduler maintenance = new EventDeliveryMaintenanceScheduler(
                () -> 0,
                () -> new EventDeliveryBacklog(0, Optional.empty()),
                metrics);

        scheduler.deliverPendingEvents();
        maintenance.maintainDeliveryState();

        assertEquals(
                1.0,
                registry.get("baton.watch.scheduler.failures")
                        .tag("operation", "event_delivery")
                        .counter()
                        .count());
        assertEquals(0.0, registry.get("baton.watch.event.delivery.backlog").gauge().value());
    }
}
