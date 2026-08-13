package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.check.ApacheUrlChecker;
import com.personal.baton.watch.adapter.out.external.check.CheckerLimits;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcCheckWorkPersistenceAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcMonitorPersistenceAdapter;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.MarkStaleProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.application.monitoring.service.GetMonitorProjectionService;
import com.personal.baton.watch.application.monitoring.service.MarkStaleProjectionsService;
import com.personal.baton.watch.application.monitoring.service.PurgeAttemptHistoryService;
import com.personal.baton.watch.application.monitoring.service.RunDueChecksService;
import com.personal.baton.watch.application.monitoring.service.SynchronizeMonitorService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

@Configuration(proxyBeanMethods = false)
public class MonitoringConfiguration {

    @Bean
    JdbcMonitorPersistenceAdapter monitorPersistenceAdapter(
            JdbcTemplate jdbcTemplate, TransactionOperations transactions) {
        return new JdbcMonitorPersistenceAdapter(jdbcTemplate, transactions);
    }

    @Bean
    JdbcCheckWorkPersistenceAdapter checkWorkPersistenceAdapter(
            JdbcTemplate jdbcTemplate, TransactionOperations transactions) {
        return new JdbcCheckWorkPersistenceAdapter(jdbcTemplate, transactions);
    }

    @Bean
    SynchronizeMonitorUseCase synchronizeMonitorUseCase(MonitorPersistencePort persistence, Clock clock) {
        return new SynchronizeMonitorService(persistence, clock);
    }

    @Bean
    GetMonitorProjectionUseCase getMonitorProjectionUseCase(MonitorPersistencePort persistence) {
        return new GetMonitorProjectionService(persistence);
    }

    @Bean
    RunDueChecksUseCase runDueChecksUseCase(
            CheckWorkPersistencePort persistence,
            ApacheUrlChecker checker,
            Clock clock,
            WatchProperties properties) {
        return new RunDueChecksService(
                persistence,
                checker,
                clock,
                properties.leaseDuration(),
                properties.checkInterval(),
                properties.internalFailureRetryInterval(),
                properties.checkBatchSize());
    }

    @Bean
    MarkStaleProjectionsUseCase markStaleProjectionsUseCase(
            MonitorPersistencePort persistence, Clock clock, WatchProperties properties) {
        return new MarkStaleProjectionsService(
                persistence,
                clock,
                properties.staleAfter(),
                properties.maintenanceBatchSize());
    }

    @Bean
    PurgeAttemptHistoryUseCase purgeAttemptHistoryUseCase(
            CheckWorkPersistencePort persistence, Clock clock, WatchProperties properties) {
        return new PurgeAttemptHistoryService(
                persistence,
                clock,
                properties.retention(),
                properties.maintenanceBatchSize());
    }

    @Bean
    ApacheUrlChecker urlChecker(WatchProperties properties) {
        WatchProperties.Http http = properties.http();
        CheckerLimits limits = new CheckerLimits(
                http.connectTimeout(),
                http.responseTimeout(),
                http.totalTimeout(),
                http.maxResponseBytes(),
                http.maxRedirects(),
                http.maxHeaderCount(),
                http.maxHeaderLineLength());
        return new ApacheUrlChecker(
                limits,
                http.dnsThreads(),
                http.dnsQueueCapacity(),
                http.requestThreads(),
                http.requestQueueCapacity());
    }
}
