package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeteredPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    void preservesCheckClaimEvidenceWhenFinalizationFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CheckWorkPersistencePort delegate = mock(CheckWorkPersistencePort.class);
        ClaimedCheck claim = new ClaimedCheck(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new TargetUrl("https://example.com/health"),
                NOW,
                true);
        CheckFinalization finalization = new CheckFinalization(
                claim.attemptId(),
                claim.leaseToken(),
                CheckObservation.internalFailure(),
                NOW,
                NOW.plusSeconds(30));
        when(delegate.claimDueCheck(LEASE)).thenReturn(Optional.of(claim));
        when(delegate.finalizeCheck(finalization)).thenThrow(new IllegalStateException("database unavailable"));
        MeteredCheckWorkPersistence persistence =
                new MeteredCheckWorkPersistence(delegate, new MonitoringMetrics(registry));

        persistence.claimDueCheck(LEASE);
        assertThrows(IllegalStateException.class, () -> persistence.finalizeCheck(finalization));

        assertEquals(1.0, registry.get("baton.watch.check.claimed").counter().count());
        assertEquals(1.0, registry.get("baton.watch.check.lease.recoveries").counter().count());
        assertEquals(
                1.0,
                registry.get("baton.watch.check.finalizations")
                        .tag("status", "failure")
                        .counter()
                        .count());
    }

    @Test
    void preservesDeliveryClaimEvidenceWhenFinalizationFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HealthChangeEventDeliveryPersistencePort delegate =
                mock(HealthChangeEventDeliveryPersistencePort.class);
        ClaimedHealthChangeEvent claim = claimedEvent();
        EventDeliveryFinalization finalization = new EventDeliveryFinalization(
                claim.payload().eventId(),
                claim.leaseToken(),
                EventDeliveryObservation.failure(EventDeliveryOutcome.INTERNAL_FAILURE),
                NOW,
                NOW.plusSeconds(10));
        when(delegate.claimPendingEvent(LEASE)).thenReturn(Optional.of(claim));
        when(delegate.finalizeDelivery(finalization)).thenThrow(new IllegalStateException("database unavailable"));
        MeteredHealthChangeEventDeliveryPersistence persistence =
                new MeteredHealthChangeEventDeliveryPersistence(
                        delegate, new MonitoringMetrics(registry));

        persistence.claimPendingEvent(LEASE);
        assertThrows(IllegalStateException.class, () -> persistence.finalizeDelivery(finalization));

        assertEquals(1.0, registry.get("baton.watch.event.delivery.claimed").counter().count());
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.lease.recoveries").counter().count());
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.finalizations")
                        .tag("status", "failure")
                        .counter()
                        .count());
    }

    private static ClaimedHealthChangeEvent claimedEvent() {
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
                2,
                NOW,
                true);
    }
}
