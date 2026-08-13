package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(prefix = "watch.event-delivery", name = "enabled")
final class EventDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventDeliveryScheduler.class);

    private final RunEventDeliveriesUseCase runEventDeliveries;
    private final MonitoringMetrics metrics;

    EventDeliveryScheduler(RunEventDeliveriesUseCase runEventDeliveries, MonitoringMetrics metrics) {
        this.runEventDeliveries = runEventDeliveries;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${watch.event-delivery.poll-interval}",
            scheduler = WorkerSchedulingConfiguration.EVENT_DELIVERY_TASK_SCHEDULER)
    void deliverPendingEvents() {
        EventDeliveryBatchResult result = runEventDeliveries.runEventDeliveries();
        metrics.recordEventDeliveryBatch(result);
        if (result.claimed() > 0) {
            log.info(
                    "health-change delivery batch completed claimed={} delivered={} retry={} replayed={} stale={}",
                    result.claimed(),
                    result.delivered(),
                    result.retryScheduled(),
                    result.alreadyDelivered(),
                    result.staleClaims());
        }
    }
}
