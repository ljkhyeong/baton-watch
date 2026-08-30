package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Conditional(EventDeliveryConfiguration.EnabledCondition.class)
final class EventDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventDeliveryScheduler.class);

    private final RunEventDeliveriesUseCase runEventDeliveries;

    EventDeliveryScheduler(RunEventDeliveriesUseCase runEventDeliveries) {
        this.runEventDeliveries = runEventDeliveries;
    }

    @Scheduled(
            fixedDelayString = "${watch.event-delivery.poll-interval}",
            scheduler = WorkerSchedulingConfiguration.EVENT_DELIVERY_TASK_SCHEDULER)
    void deliverPendingEvents() {
        EventDeliveryBatchResult result = runEventDeliveries.runEventDeliveries();
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
