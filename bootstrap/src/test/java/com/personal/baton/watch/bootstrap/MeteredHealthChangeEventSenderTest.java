package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
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
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.attempts")
                        .tag("outcome", "dns_failure")
                        .counter()
                        .count());
    }

    @Test
    void convertsUnexpectedSenderErrorsAndRecordsTheInternalOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> {
                    throw new IllegalStateException("sensitive callback detail");
                },
                new MonitoringMetrics(registry));

        EventDeliveryObservation observation = sender.send(null);

        assertEquals(EventDeliveryOutcome.INTERNAL_FAILURE, observation.outcome());
        assertEquals(
                1.0,
                registry.get("baton.watch.event.delivery.attempts")
                        .tag("outcome", "internal_failure")
                        .counter()
                        .count());
    }

    @Test
    void telemetryFailureCannotTurnAnAcknowledgedDeliveryIntoARetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (id.getName().equals("baton.watch.event.delivery.attempts")) {
                    throw new IllegalStateException("registry unavailable");
                }
                return id;
            }
        });
        MeteredHealthChangeEventSender sender = new MeteredHealthChangeEventSender(
                ignored -> EventDeliveryObservation.delivered(204),
                new MonitoringMetrics(registry));

        EventDeliveryObservation observation = sender.send(null);

        assertEquals(EventDeliveryOutcome.DELIVERED, observation.outcome());
    }
}
