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
        try {
            observation = Objects.requireNonNull(delegate.check(targetUrl), "check observation");
        } catch (RuntimeException ignored) {
            observation = CheckObservation.internalFailure();
        }
        try {
            metrics.recordCheckAttempt(observation);
        } catch (RuntimeException ignored) {
            // 텔레메트리 실패가 점검 결과나 다음 점검 일정을 바꾸어서는 안 된다.
        }
        return observation;
    }
}
