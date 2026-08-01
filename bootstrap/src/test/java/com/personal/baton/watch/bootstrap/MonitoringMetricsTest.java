package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonitoringMetricsTest {

    @Test
    void emitsOnlyBoundedCheckAndDeliveryTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        metrics.recordCheckBatch(new DueCheckBatchResult(3, 1, 1, 1));
        metrics.recordEventDeliveryBatch(new EventDeliveryBatchResult(
                4,
                1,
                1,
                1,
                1,
                Map.of(
                        EventDeliveryOutcome.DELIVERED, 1,
                        EventDeliveryOutcome.CONNECT_TIMEOUT, 1,
                        EventDeliveryOutcome.HTTP_SERVER_ERROR, 1,
                        EventDeliveryOutcome.INTERNAL_FAILURE, 1)));
        metrics.recordEventDeliveryAttempt(EventDeliveryOutcome.CONNECT_TIMEOUT);
        metrics.recordSchedulerFailure("event_delivery");

        assertEquals(3.0, registry.get("baton.watch.check.claimed").counter().count());
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
                registry.get("baton.watch.scheduler.failures")
                        .tag("operation", "event_delivery")
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
    }

    @Test
    void updatesBacklogGaugesWithoutIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        metrics.updateEventDeliveryBacklog(7, Optional.of(Duration.ofSeconds(91)));

        assertEquals(7.0, registry.get("baton.watch.event.delivery.backlog").gauge().value());
        assertEquals(91.0, registry.get("baton.watch.event.delivery.oldest.age").gauge().value());
    }
}
