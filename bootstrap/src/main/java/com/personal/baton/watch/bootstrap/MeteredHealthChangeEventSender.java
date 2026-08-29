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
        Timer.Sample sample = null;
        try {
            sample = metrics.eventDeliveryStarted();
        } catch (RuntimeException ignored) {
            // 텔레메트리 실패가 이벤트 전달을 막아서는 안 된다.
        }
        EventDeliveryObservation observation = null;
        try {
            observation = delegate.send(payload);
            return observation;
        } finally {
            EventDeliveryOutcome recordedOutcome = observation == null
                    ? EventDeliveryOutcome.INTERNAL_FAILURE
                    : observation.outcome();
            if (sample != null) {
                Timer.Sample completedSample = sample;
                BestEffortMetrics.record(() ->
                        metrics.eventDeliveryFinished(completedSample, recordedOutcome));
            }
            BestEffortMetrics.record(() ->
                    metrics.recordEventDeliveryAttempt(recordedOutcome));
        }
    }
}
