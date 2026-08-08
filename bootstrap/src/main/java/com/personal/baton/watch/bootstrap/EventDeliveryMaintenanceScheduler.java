package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.port.in.GetEventDeliveryBacklogUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeDeliveredEventsUseCase;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class EventDeliveryMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventDeliveryMaintenanceScheduler.class);

    private final PurgeDeliveredEventsUseCase purgeDeliveredEvents;
    private final GetEventDeliveryBacklogUseCase getBacklog;
    private final MonitoringMetrics metrics;

    EventDeliveryMaintenanceScheduler(
            PurgeDeliveredEventsUseCase purgeDeliveredEvents,
            GetEventDeliveryBacklogUseCase getBacklog,
            MonitoringMetrics metrics) {
        this.purgeDeliveredEvents = Objects.requireNonNull(purgeDeliveredEvents, "purgeDeliveredEvents");
        this.getBacklog = Objects.requireNonNull(getBacklog, "getBacklog");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Scheduled(
            fixedDelayString = "${watch.event-delivery.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void maintainDeliveryState() {
        try {
            int purged = purgeDeliveredEvents.purgeDeliveredEvents();
            metrics.recordPurgedDeliveredEvents(purged);
            if (purged > 0) {
                log.info("health-change delivery maintenance completed purged={}", purged);
            }
        } catch (RuntimeException exception) {
            metrics.recordSchedulerFailure("event_retention");
            log.error("health-change delivery maintenance failed failureType={}", exception.getClass().getSimpleName());
        }
        updateBacklogSafely();
    }

    private void updateBacklogSafely() {
        try {
            EventDeliveryBacklog backlog = getBacklog.getEventDeliveryBacklog();
            metrics.updateEventDeliveryBacklog(backlog.pendingCount(), backlog.oldestEventAge());
        } catch (RuntimeException exception) {
            metrics.recordSchedulerFailure("event_backlog");
            log.error("health-change delivery backlog refresh failed failureType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
