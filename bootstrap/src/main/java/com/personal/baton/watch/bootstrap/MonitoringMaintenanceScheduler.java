package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.port.in.MonitoringMaintenanceUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 점검을 중지한 환경에서도 오래된 상태 처리와 이력 정리를 계속 실행한다. */
@Component
final class MonitoringMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringMaintenanceScheduler.class);

    private final MonitoringMaintenanceUseCase maintenance;
    private final MonitoringMetrics metrics;

    MonitoringMaintenanceScheduler(MonitoringMaintenanceUseCase maintenance, MonitoringMetrics metrics) {
        this.maintenance = maintenance;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void markStaleProjections() {
        int stale = maintenance.markStaleProjectionsUnknown();
        metrics.recordStaleProjections(stale);
        if (stale > 0) {
            log.info("오래된 모니터 상태 처리 완료 count={}", stale);
        }
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void purgeAttemptHistory() {
        int purged = maintenance.purgeAttemptHistory();
        metrics.recordPurgedAttempts(purged);
        if (purged > 0) {
            log.info("모니터 점검 이력 정리 완료 count={}", purged);
        }
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void updateDatabaseClockOffset() {
        metrics.updateDatabaseClockOffset(maintenance.databaseClockOffset());
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void refreshCheckScheduleDelay() {
        metrics.updateCheckScheduleDelay(maintenance.checkScheduleDelay());
    }
}
