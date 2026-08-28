package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;

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
        EventDeliveryObservation observation;
        try {
            observation = Objects.requireNonNull(delegate.send(payload), "delivery observation");
        } catch (RuntimeException ignored) {
            observation = EventDeliveryObservation.internalFailure();
        }
        if (sample != null) {
            try {
                metrics.eventDeliveryFinished(sample, observation.outcome());
            } catch (RuntimeException ignored) {
                // 텔레메트리 실패가 확인된 전달을 재시도로 바꾸어서는 안 된다.
            }
        }
        try {
            metrics.recordEventDeliveryAttempt(observation.outcome());
        } catch (RuntimeException ignored) {
            // 텔레메트리 실패가 확인된 전달을 재시도로 바꾸어서는 안 된다.
        }
        return observation;
    }
}
