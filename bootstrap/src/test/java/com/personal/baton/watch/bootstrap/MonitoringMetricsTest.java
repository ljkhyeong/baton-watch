package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MonitoringMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void emitsOnlyBoundedCheckAndDeliveryTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        assertEquals(0.0, registry.get("baton.watch.check.inflight").gauge().value());
        assertEquals(0.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());

        metrics.updateCheckScheduleDelay(new DueCheckBatchResult(
                3,
                1,
                1,
                1,
                Duration.ofSeconds(17)));
        ClaimedCheck claimedCheck = claimedCheck(true);
        metrics.recordCheckClaims(List.of(claimedCheck, claimedCheck, claimedCheck));
        metrics.recordCheckFinalization(CheckFinalizationStatus.STALE_CLAIM);
        metrics.recordCheckAttempt(CheckObservation.failure(
                CheckOutcome.CONNECT_TIMEOUT,
                Duration.ofMillis(125),
                0,
                0));
        ClaimedHealthChangeEvent claimedEvent = claimedEvent(true);
        metrics.recordEventDeliveryClaims(List.of(claimedEvent, claimedEvent, claimedEvent, claimedEvent));
        metrics.recordEventDeliveryFinalization(
                new EventDeliveryFinalization(
                        claimedEvent.payload().eventId(),
                        claimedEvent.leaseToken(),
                        EventDeliveryObservation.failure(EventDeliveryOutcome.CONNECT_TIMEOUT),
                        NOW,
                        NOW.plusSeconds(1)),
                EventDeliveryFinalizationStatus.APPLIED);
        metrics.recordEventDeliveryAttempt(EventDeliveryOutcome.CONNECT_TIMEOUT);
        Timer.Sample deliverySample = metrics.eventDeliveryStarted();
        assertEquals(1.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());
        metrics.eventDeliveryFinished(deliverySample, EventDeliveryOutcome.CONNECT_TIMEOUT);

        assertEquals(3.0, registry.get("baton.watch.check.claimed").counter().count());
        assertEquals(3.0, registry.get("baton.watch.check.lease.recoveries").counter().count());
        assertEquals(4.0, registry.get("baton.watch.event.delivery.claimed").counter().count());
        assertEquals(
                4.0,
                registry.get("baton.watch.event.delivery.lease.recoveries").counter().count());
        assertEquals(17.0, registry.get("baton.watch.check.schedule.delay").gauge().value());
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
        assertEquals(0.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());
        assertEquals(
                1L,
                registry.get("baton.watch.event.delivery.duration")
                        .tag("outcome", "connect_timeout")
                        .timer()
                        .count());
        assertTrue(registry.find("baton.watch.event.delivery.finalizations").meters().stream()
                .allMatch(meter -> meter.getId().getTag("resourceReference") == null));
        assertTrue(registry.find("baton.watch.check.attempts").meters().stream()
                .allMatch(meter -> meter.getId().getTags().stream()
                        .allMatch(tag -> tag.getKey().equals("outcome"))));
        assertTrue(registry.find("baton.watch.check.duration").meters().stream()
                .allMatch(meter -> meter.getId().getTags().stream()
                        .allMatch(tag -> tag.getKey().equals("outcome"))));

        metrics.updateCheckScheduleDelay(new DueCheckBatchResult(0, 0, 0, 0, Duration.ZERO));
        assertEquals(0.0, registry.get("baton.watch.check.schedule.delay").gauge().value());
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

    @Test
    void reportsSignedDatabaseClockOffsetInSeconds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        metrics.updateDatabaseClockOffset(Duration.ofMillis(-1_500));

        assertEquals(-1.5, registry.get("baton.watch.database.clock.offset").gauge().value());
    }

    private static ClaimedCheck claimedCheck(boolean recoveredLease) {
        return new ClaimedCheck(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new TargetUrl("https://example.com/health"),
                NOW.minusSeconds(1),
                NOW,
                recoveredLease);
    }

    private static ClaimedHealthChangeEvent claimedEvent(boolean recoveredLease) {
        return new ClaimedHealthChangeEvent(
                new HealthChangeEventPayload(
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        new ResourceReference("resource-1"),
                        new SourceRevision(1),
                        Optional.empty(),
                        Health.UNKNOWN,
                        Health.HEALTHY,
                        NOW),
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                1,
                NOW,
                recoveredLease);
    }
}
