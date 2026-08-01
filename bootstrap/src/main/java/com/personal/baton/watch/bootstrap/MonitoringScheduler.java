package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.port.in.MarkStaleProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import java.util.Objects;
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
    private final MonitoringMetrics metrics;

    public MonitoringScheduler(
            RunDueChecksUseCase runDueChecks,
            MarkStaleProjectionsUseCase markStaleProjections,
            PurgeAttemptHistoryUseCase purgeAttemptHistory,
            MonitoringMetrics metrics) {
        this.runDueChecks = Objects.requireNonNull(runDueChecks, "runDueChecks");
        this.markStaleProjections = Objects.requireNonNull(markStaleProjections, "markStaleProjections");
        this.purgeAttemptHistory = Objects.requireNonNull(purgeAttemptHistory, "purgeAttemptHistory");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Scheduled(fixedDelayString = "${watch.poll-interval}")
    void checkDueMonitors() {
        try {
            DueCheckBatchResult result = runDueChecks.runDueChecks();
            metrics.recordCheckBatch(result);
            if (result.claimed() > 0) {
                log.info(
                        "monitor check batch completed claimed={} applied={} replayed={} stale={}",
                        result.claimed(),
                        result.applied(),
                        result.alreadyFinalized(),
                        result.staleClaims());
            }
        } catch (RuntimeException exception) {
            metrics.recordSchedulerFailure("check");
            log.error("monitor check batch failed failureType={}", exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${watch.maintenance-interval}")
    void maintainMonitoringState() {
        try {
            int stale = markStaleProjections.markStaleProjectionsUnknown();
            int purged = purgeAttemptHistory.purgeAttemptHistory();
            metrics.recordMonitoringMaintenance(stale, purged);
            if (stale > 0 || purged > 0) {
                log.info("monitor maintenance completed stale={} purged={}", stale, purged);
            }
        } catch (RuntimeException exception) {
            metrics.recordSchedulerFailure("monitoring_maintenance");
            log.error("monitor maintenance failed failureType={}", exception.getClass().getSimpleName());
        }
    }
}
