package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MonitoringMetricsTest {

    @Test
    void emitsOnlyBoundedCheckAndDeliveryTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        metrics.recordCheckBatch(new DueCheckBatchResult(3, 1, 1, 1));
        metrics.recordCheckAttempt(CheckObservation.failure(
                CheckOutcome.CONNECT_TIMEOUT,
                Duration.ofMillis(125),
                0,
                0));
        metrics.recordEventDeliveryBatch(new EventDeliveryBatchResult(
                4,
                1,
                1,
                1,
                1));
        metrics.recordEventDeliveryAttempt(EventDeliveryOutcome.CONNECT_TIMEOUT);

        assertEquals(3.0, registry.get("baton.watch.check.claimed").counter().count());
        assertEquals(
                1.0,
                registry.get("baton.watch.check.attempts")
                        .tag("outcome", "connect_timeout")
                        .counter()
                        .count());
        assertEquals(
                125.0,
                registry.get("baton.watch.check.duration")
                        .tag("outcome", "connect_timeout")
                        .timer()
                        .totalTime(TimeUnit.MILLISECONDS));
        assertEquals(
                1.0,
                registry.get("baton.watch.check.finalizations")
                        .tag("status", "stale_claim")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.finalizations")
                        .tag("status", "retry_scheduled")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.attempts")
                        .tag("outcome", "connect_timeout")
                        .counter()
                        .count());
        assertTrue(registry.find("baton.watch.event.delivery.finalizations").meters().stream()
                .allMatch(meter -> meter.getId().getTag("resourceReference") == null));
        assertTrue(registry.find("baton.watch.check.attempts").meters().stream()
                .allMatch(meter -> meter.getId().getTags().stream()
                        .allMatch(tag -> tag.getKey().equals("outcome"))));
        assertTrue(registry.find("baton.watch.check.duration").meters().stream()
                .allMatch(meter -> meter.getId().getTags().stream()
                        .allMatch(tag -> tag.getKey().equals("outcome"))));
    }

    @Test
    void updatesBacklogGaugesWithoutIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        metrics.updateEventDeliveryBacklog(new EventDeliveryBacklog(
                7,
                Optional.of(Duration.ofSeconds(91))));

        assertEquals(7.0, registry.get("baton.watch.event.delivery.backlog").gauge().value());
        assertEquals(91.0, registry.get("baton.watch.event.delivery.oldest.age").gauge().value());
    }

    @Test
    void recordsMonitoringMaintenanceItemsIndependently() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        metrics.recordStaleProjections(2);
        metrics.recordPurgedAttempts(3);
        metrics.recordPurgedDeliveredEvents(4);

        assertEquals(
                2.0,
                registry.get("baton.watch.maintenance.items")
                        .tag("operation", "stale_projection")
                        .counter()
                        .count());
        assertEquals(
                3.0,
                registry.get("baton.watch.maintenance.items")
                        .tag("operation", "attempt_purged")
                        .counter()
                        .count());
        assertEquals(
                4.0,
                registry.get("baton.watch.maintenance.items")
                        .tag("operation", "delivered_event_purged")
                        .counter()
                        .count());
    }
}
