package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.port.out.UrlChecker;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.util.Objects;

final class MeteredUrlChecker implements UrlChecker {

    private final UrlChecker delegate;
    private final MonitoringMetrics metrics;

    MeteredUrlChecker(UrlChecker delegate, MonitoringMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public CheckObservation check(TargetUrl targetUrl) {
        CheckObservation observation;
        metrics.checkStarted();
        try {
            observation = Objects.requireNonNull(delegate.check(targetUrl), "check observation");
        } catch (RuntimeException ignored) {
            observation = CheckObservation.internalFailure();
        } finally {
            metrics.checkFinished();
        }
        CheckObservation recordedObservation = observation;
        BestEffortMetrics.record(() -> metrics.recordCheckAttempt(recordedObservation));
        return observation;
    }
}
