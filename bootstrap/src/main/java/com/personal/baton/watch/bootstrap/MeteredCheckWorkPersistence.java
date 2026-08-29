package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class MeteredCheckWorkPersistence implements CheckWorkPersistencePort {

    private final CheckWorkPersistencePort delegate;
    private final MonitoringMetrics metrics;

    MeteredCheckWorkPersistence(CheckWorkPersistencePort delegate, MonitoringMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public List<ClaimedCheck> claimDueChecks(Duration leaseDuration, int limit) {
        List<ClaimedCheck> claimed = delegate.claimDueChecks(leaseDuration, limit);
        BestEffortMetrics.record(() -> metrics.recordCheckClaims(claimed));
        return claimed;
    }

    @Override
    public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
        try {
            CheckFinalizationStatus status = delegate.finalizeCheck(finalization);
            BestEffortMetrics.record(() -> metrics.recordCheckFinalization(status));
            return status;
        } catch (RuntimeException failure) {
            BestEffortMetrics.record(metrics::recordCheckFinalizationFailure);
            throw failure;
        }
    }

    @Override
    public int purgeAttempts(Instant completedBefore, int limit) {
        return delegate.purgeAttempts(completedBefore, limit);
    }
}
