package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.port.out.UrlChecker;
import com.personal.baton.watch.domain.monitoring.TargetUrl;

final class MeteredUrlChecker implements UrlChecker {

    private final UrlChecker delegate;
    private final MonitoringMetrics metrics;

    MeteredUrlChecker(UrlChecker delegate, MonitoringMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public CheckObservation check(TargetUrl targetUrl) {
        CheckObservation observation = null;
        metrics.checkStarted();
        try {
            observation = delegate.check(targetUrl);
            return observation;
        } finally {
            metrics.checkFinished();
            CheckObservation recordedObservation = observation == null
                    ? CheckObservation.internalFailure()
                    : observation;
            BestEffortMetrics.record(() -> metrics.recordCheckAttempt(recordedObservation));
        }
    }
}
