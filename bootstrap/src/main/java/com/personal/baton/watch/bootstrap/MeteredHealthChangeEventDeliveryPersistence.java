package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

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
    public Optional<ClaimedHealthChangeEvent> claimPendingEvent(Duration leaseDuration) {
        Optional<ClaimedHealthChangeEvent> claimed = delegate.claimPendingEvent(leaseDuration);
        claimed.ifPresent(metrics::recordEventDeliveryClaim);
        return claimed;
    }

    @Override
    public EventDeliveryFinalizationStatus finalizeDelivery(EventDeliveryFinalization finalization) {
        try {
            EventDeliveryFinalizationStatus status = delegate.finalizeDelivery(finalization);
            metrics.recordEventDeliveryFinalization(finalization, status);
            return status;
        } catch (RuntimeException failure) {
            metrics.recordEventDeliveryFinalizationFailure();
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
