package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.port.in.GetCheckScheduleDelayUseCase;
import com.personal.baton.watch.application.monitoring.port.in.GetDatabaseClockOffsetUseCase;
import com.personal.baton.watch.application.monitoring.port.in.MarkStaleProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 점검을 중지한 환경에서도 오래된 상태 처리와 이력 정리를 계속 실행한다. */
@Component
final class MonitoringMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringMaintenanceScheduler.class);

    private final MarkStaleProjectionsUseCase markStaleProjections;
    private final PurgeAttemptHistoryUseCase purgeAttemptHistory;
    private final GetDatabaseClockOffsetUseCase getDatabaseClockOffset;
    private final GetCheckScheduleDelayUseCase getCheckScheduleDelay;
    private final MonitoringMetrics metrics;

    MonitoringMaintenanceScheduler(
            MarkStaleProjectionsUseCase markStaleProjections,
            PurgeAttemptHistoryUseCase purgeAttemptHistory,
            GetDatabaseClockOffsetUseCase getDatabaseClockOffset,
            GetCheckScheduleDelayUseCase getCheckScheduleDelay,
            MonitoringMetrics metrics) {
        this.markStaleProjections = markStaleProjections;
        this.purgeAttemptHistory = purgeAttemptHistory;
        this.getDatabaseClockOffset = getDatabaseClockOffset;
        this.getCheckScheduleDelay = getCheckScheduleDelay;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void markStaleProjections() {
        int stale = markStaleProjections.markStaleProjectionsUnknown();
        metrics.recordStaleProjections(stale);
        if (stale > 0) {
            log.info("오래된 모니터 상태 처리 완료 count={}", stale);
        }
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void purgeAttemptHistory() {
        int purged = purgeAttemptHistory.purgeAttemptHistory();
        metrics.recordPurgedAttempts(purged);
        if (purged > 0) {
            log.info("모니터 점검 이력 정리 완료 count={}", purged);
        }
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void updateDatabaseClockOffset() {
        metrics.updateDatabaseClockOffset(getDatabaseClockOffset.getDatabaseClockOffset());
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void refreshCheckScheduleDelay() {
        metrics.updateCheckScheduleDelay(getCheckScheduleDelay.getCheckScheduleDelay());
    }
}
