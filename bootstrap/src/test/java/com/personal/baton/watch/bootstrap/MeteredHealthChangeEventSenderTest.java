package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MeteredHealthChangeEventSenderTest {

    @Test
    void recordsEachAttemptBeforeBatchFinalizationCanFail() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE),
                new MonitoringMetrics(registry));

        EventDeliveryObservation observation = sender.send(null);

        assertEquals(EventDeliveryOutcome.DNS_FAILURE, observation.outcome());
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.attempts")
                        .tag("outcome", "dns_failure")
                        .counter()
                        .count());
        assertEquals(0.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());
        assertEquals(
                1L,
                registry.get("baton.watch.event.delivery.duration")
                        .tag("outcome", "dns_failure")
                        .timer()
                        .count());
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
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.attempts")
                        .tag("outcome", "internal_failure")
                        .counter()
                        .count());
    }

    @Test
    void recordsNullSenderResultsWithoutChangingThem() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> null,
                new MonitoringMetrics(registry));

        EventDeliveryObservation observation = sender.send(null);

        assertNull(observation);
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.attempts")
                        .tag("outcome", "internal_failure")
                        .counter()
                        .count());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "baton.watch.event.delivery.attempts",
            "baton.watch.event.delivery.duration"
    })
    void telemetryFailureCannotTurnAnAcknowledgedDeliveryIntoARetry(String failingMeter) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (id.getName().equals(failingMeter)) {
                    throw new IllegalStateException("registry unavailable");
                }
                return id;
            }
        });
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> EventDeliveryObservation.forHttpStatus(204),
                new MonitoringMetrics(registry));

        EventDeliveryObservation observation = sender.send(null);

        assertEquals(EventDeliveryOutcome.DELIVERED, observation.outcome());
        assertEquals(0.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());
    }

    @Test
    void timerStartFailureDoesNotBlockDeliveryOrLeaveAnInFlightCount() {
        Clock clock = mock(Clock.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        MonitoringMetrics metrics = new MonitoringMetrics(registry);
        when(clock.monotonicTime()).thenThrow(new IllegalStateException("clock unavailable"));
        EventDeliveryObservation expected = EventDeliveryObservation.forHttpStatus(204);
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> expected, metrics);

        assertSame(expected, sender.send(null));
        assertEquals(0.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());
        assertEquals(1.0, registry.get("baton.watch.event.delivery.attempts")
                .tag("outcome", "delivered")
                .counter()
                .count());
    }
}
