package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(prefix = "watch", name = "check-enabled", matchIfMissing = true)
public final class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final RunDueChecksUseCase runDueChecks;
    private final MonitoringMetrics metrics;

    public MonitoringScheduler(RunDueChecksUseCase runDueChecks, MonitoringMetrics metrics) {
        this.runDueChecks = runDueChecks;
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
                    "모니터 점검 배치 완료 claimed={} applied={} replayed={} stale={}",
                    result.claimed(),
                    result.applied(),
                    result.alreadyFinalized(),
                    result.staleClaims());
        }
    }
}
