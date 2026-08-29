package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.port.in.GetDatabaseClockOffsetUseCase;
import com.personal.baton.watch.application.monitoring.port.in.MarkStaleProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final RunDueChecksUseCase runDueChecks;
    private final MarkStaleProjectionsUseCase markStaleProjections;
    private final PurgeAttemptHistoryUseCase purgeAttemptHistory;
    private final GetDatabaseClockOffsetUseCase getDatabaseClockOffset;
    private final MonitoringMetrics metrics;

    public MonitoringScheduler(
            RunDueChecksUseCase runDueChecks,
            MarkStaleProjectionsUseCase markStaleProjections,
            PurgeAttemptHistoryUseCase purgeAttemptHistory,
            GetDatabaseClockOffsetUseCase getDatabaseClockOffset,
            MonitoringMetrics metrics) {
        this.runDueChecks = runDueChecks;
        this.markStaleProjections = markStaleProjections;
        this.purgeAttemptHistory = purgeAttemptHistory;
        this.getDatabaseClockOffset = getDatabaseClockOffset;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${watch.poll-interval}",
            scheduler = WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER)
    void checkDueMonitors() {
        DueCheckBatchResult result = runDueChecks.runDueChecks();
        metrics.updateCheckScheduleDelay(result);
        if (result.claimed() > 0) {
            log.info(
                    "monitor check batch completed claimed={} applied={} replayed={} stale={}",
                    result.claimed(),
                    result.applied(),
                    result.alreadyFinalized(),
                    result.staleClaims());
        }
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void markStaleProjections() {
        int stale = markStaleProjections.markStaleProjectionsUnknown();
        metrics.recordStaleProjections(stale);
        if (stale > 0) {
            log.info("monitor stale projections marked count={}", stale);
        }
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void purgeAttemptHistory() {
        int purged = purgeAttemptHistory.purgeAttemptHistory();
        metrics.recordPurgedAttempts(purged);
        if (purged > 0) {
            log.info("monitor attempt history purged count={}", purged);
        }
    }

    @Scheduled(
            fixedDelayString = "${watch.maintenance-interval}",
            scheduler = WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER)
    void updateDatabaseClockOffset() {
        metrics.updateDatabaseClockOffset(getDatabaseClockOffset.getDatabaseClockOffset());
    }
}
