package com.personal.baton.watch.bootstrap;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 점검, 콜백 전달, 데이터베이스 유지보수를 제한된 스케줄러에서 격리한다. */
@Configuration(proxyBeanMethods = false)
final class WorkerSchedulingConfiguration {

    static final String MONITORING_TASK_SCHEDULER = "monitoringTaskScheduler";
    static final String EVENT_DELIVERY_TASK_SCHEDULER = "eventDeliveryTaskScheduler";
    static final String MAINTENANCE_TASK_SCHEDULER = "maintenanceTaskScheduler";

    @Bean
    RedactingScheduledTaskErrorHandler scheduledTaskErrorHandler() {
        return new RedactingScheduledTaskErrorHandler();
    }

    @Bean(name = MONITORING_TASK_SCHEDULER)
    ThreadPoolTaskScheduler monitoringTaskScheduler(
            ThreadPoolTaskSchedulerBuilder builder,
            RedactingScheduledTaskErrorHandler errorHandler) {
        return isolatedScheduler(builder, errorHandler, "watch-monitoring-");
    }

    @Bean(name = EVENT_DELIVERY_TASK_SCHEDULER)
    ThreadPoolTaskScheduler eventDeliveryTaskScheduler(
            ThreadPoolTaskSchedulerBuilder builder,
            RedactingScheduledTaskErrorHandler errorHandler) {
        return isolatedScheduler(builder, errorHandler, "watch-event-delivery-");
    }

    @Bean(name = MAINTENANCE_TASK_SCHEDULER)
    ThreadPoolTaskScheduler maintenanceTaskScheduler(
            ThreadPoolTaskSchedulerBuilder builder,
            RedactingScheduledTaskErrorHandler errorHandler) {
        return isolatedScheduler(builder, errorHandler, "watch-maintenance-");
    }

    private static ThreadPoolTaskScheduler isolatedScheduler(
            ThreadPoolTaskSchedulerBuilder builder,
            RedactingScheduledTaskErrorHandler errorHandler,
            String threadNamePrefix) {
        return builder
                .poolSize(1)
                .threadNamePrefix(threadNamePrefix)
                .additionalCustomizers(scheduler -> {
                    scheduler.setErrorHandler(errorHandler);
                    scheduler.setRemoveOnCancelPolicy(true);
                    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                })
                .build();
    }
}
