package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Conditional(MonitoringScheduler.CheckEnabledCondition.class)
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
        if (result.claimed() > 0) {
            log.info(
                    "모니터 점검 배치 완료 claimed={} applied={} replayed={} stale={}",
                    result.claimed(),
                    result.applied(),
                    result.alreadyFinalized(),
                    result.staleClaims());
        }
    }

    static final class CheckEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty("watch.check-enabled", Boolean.class, true);
        }
    }
}
