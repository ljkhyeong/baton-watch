package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class MeteredHealthChangeEventDeliveryPersistence
        implements HealthChangeEventDeliveryPersistencePort {

    private final HealthChangeEventDeliveryPersistencePort delegate;
    private final MonitoringMetrics metrics;

    MeteredHealthChangeEventDeliveryPersistence(
            HealthChangeEventDeliveryPersistencePort delegate,
            MonitoringMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public List<ClaimedHealthChangeEvent> claimPendingEvents(Duration leaseDuration, int limit) {
        List<ClaimedHealthChangeEvent> claimed = delegate.claimPendingEvents(leaseDuration, limit);
        BestEffortMetrics.record(() -> metrics.recordEventDeliveryClaims(claimed));
        return claimed;
    }

    @Override
    public EventDeliveryFinalizationStatus finalizeDelivery(EventDeliveryFinalization finalization) {
        try {
            EventDeliveryFinalizationStatus status = delegate.finalizeDelivery(finalization);
            BestEffortMetrics.record(() -> metrics.recordEventDeliveryFinalization(finalization, status));
            return status;
        } catch (RuntimeException failure) {
            BestEffortMetrics.record(metrics::recordEventDeliveryFinalizationFailure);
            throw failure;
        }
    }

    @Override
    public int purgeDeliveredEvents(Instant deliveredBefore, int limit) {
        return delegate.purgeDeliveredEvents(deliveredBefore, limit);
    }

    @Override
    public EventDeliveryBacklogSnapshot getBacklogSnapshot() {
        return delegate.getBacklogSnapshot();
    }
}
