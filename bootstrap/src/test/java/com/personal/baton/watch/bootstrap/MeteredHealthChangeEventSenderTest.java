package com.personal.baton.watch.bootstrap;

import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.count;
import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.timer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MeteredHealthChangeEventSenderTest {

    @Test
    void recordsEachAttemptBeforeBatchFinalizationCanFail() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE),
                new MonitoringMetrics(registry));

        EventDeliveryObservation observation = sender.send(null);

        assertEquals(EventDeliveryOutcome.DNS_FAILURE, observation.outcome());
        assertEquals(1.0, count(registry, "baton.watch.event.delivery.attempts", "outcome", "dns_failure"));
        assertEquals(0.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());
        assertEquals(1L, timer(registry, "baton.watch.event.delivery.duration", "outcome", "dns_failure").count());
    }

    @Test
    void recordsUnexpectedSenderErrorsAndPropagatesTheOriginalFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IllegalStateException expected = new IllegalStateException("sensitive callback detail");
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> {
                    throw expected;
                },
                new MonitoringMetrics(registry));

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> sender.send(null));

        assertSame(expected, actual);
        assertEquals(1.0, count(registry, "baton.watch.event.delivery.attempts", "outcome", "internal_failure"));
    }

    @Test
    void recordsNullSenderResultsWithoutChangingThem() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> null,
                new MonitoringMetrics(registry));

        EventDeliveryObservation observation = sender.send(null);

        assertNull(observation);
        assertEquals(1.0, count(registry, "baton.watch.event.delivery.attempts", "outcome", "internal_failure"));
    }
}
