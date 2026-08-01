package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import java.util.Objects;

final class MeteredHealthChangeEventSender implements HealthChangeEventSender {

    private final HealthChangeEventSender delegate;
    private final MonitoringMetrics metrics;

    MeteredHealthChangeEventSender(HealthChangeEventSender delegate, MonitoringMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public EventDeliveryObservation send(ClaimedHealthChangeEvent event) {
        EventDeliveryObservation observation;
        try {
            observation = Objects.requireNonNull(delegate.send(event), "delivery observation");
        } catch (RuntimeException ignored) {
            observation = EventDeliveryObservation.internalFailure();
        }
        try {
            metrics.recordEventDeliveryAttempt(observation.outcome());
        } catch (RuntimeException ignored) {
            // Telemetry must never change an acknowledged delivery into a retry.
        }
        return observation;
    }
}
