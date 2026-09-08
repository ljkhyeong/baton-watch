package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import io.micrometer.core.instrument.Timer;

final class MeteredHealthChangeEventSender implements HealthChangeEventSender {

    private final HealthChangeEventSender delegate;
    private final MonitoringMetrics metrics;

    MeteredHealthChangeEventSender(HealthChangeEventSender delegate, MonitoringMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public EventDeliveryObservation send(HealthChangeEventPayload payload) {
        Timer.Sample sample = metrics.eventDeliveryStarted();
        EventDeliveryObservation observation = null;
        try {
            observation = delegate.send(payload);
            return observation;
        } finally {
            EventDeliveryOutcome recordedOutcome = observation == null
                    ? EventDeliveryOutcome.INTERNAL_FAILURE
                    : observation.outcome();
            metrics.eventDeliveryFinished(sample, recordedOutcome);
            metrics.recordEventDeliveryAttempt(recordedOutcome);
        }
    }
}
