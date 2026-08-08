package com.personal.baton.watch.bootstrap;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Isolates checks, callback delivery, and database maintenance on bounded schedulers. */
@Configuration(proxyBeanMethods = false)
final class WorkerSchedulingConfiguration {

    static final String MONITORING_TASK_SCHEDULER = "monitoringTaskScheduler";
    static final String EVENT_DELIVERY_TASK_SCHEDULER = "eventDeliveryTaskScheduler";
    static final String MAINTENANCE_TASK_SCHEDULER = "maintenanceTaskScheduler";

    @Bean(name = MONITORING_TASK_SCHEDULER)
    ThreadPoolTaskScheduler monitoringTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return isolatedScheduler(builder, "watch-monitoring-");
    }

    @Bean(name = EVENT_DELIVERY_TASK_SCHEDULER)
    ThreadPoolTaskScheduler eventDeliveryTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return isolatedScheduler(builder, "watch-event-delivery-");
    }

    @Bean(name = MAINTENANCE_TASK_SCHEDULER)
    ThreadPoolTaskScheduler maintenanceTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return isolatedScheduler(builder, "watch-maintenance-");
    }

    private static ThreadPoolTaskScheduler isolatedScheduler(
            ThreadPoolTaskSchedulerBuilder builder, String threadNamePrefix) {
        return builder
                .poolSize(1)
                .threadNamePrefix(threadNamePrefix)
                .additionalCustomizers(scheduler -> {
                    scheduler.setRemoveOnCancelPolicy(true);
                    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
                    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                })
                .build();
    }
}
