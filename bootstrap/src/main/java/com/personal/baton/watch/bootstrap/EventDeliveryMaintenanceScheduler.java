package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.port.in.GetEventDeliveryBacklogUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeDeliveredEventsUseCase;
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
        this.purgeDeliveredEvents = purgeDeliveredEvents;
        this.getBacklog = getBacklog;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${watch.event-delivery.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void purgeDeliveredEventHistory() {
        int purged = purgeDeliveredEvents.purgeDeliveredEvents();
        metrics.recordPurgedDeliveredEvents(purged);
        if (purged > 0) {
            log.info("health-change delivery maintenance completed purged={}", purged);
        }
    }

    @Scheduled(
            fixedDelayString = "${watch.event-delivery.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void refreshEventDeliveryBacklog() {
        EventDeliveryBacklog backlog = getBacklog.getEventDeliveryBacklog();
        metrics.updateEventDeliveryBacklog(backlog);
    }
}
