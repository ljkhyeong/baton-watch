package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.check.ApacheUrlChecker;
import com.personal.baton.watch.adapter.out.external.check.CheckerLimits;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcCheckWorkPersistenceAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcDatabaseClockAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcMonitorPersistenceAdapter;
import com.personal.baton.watch.application.monitoring.port.in.GetCheckScheduleDelayUseCase;
import com.personal.baton.watch.application.monitoring.port.in.GetDatabaseClockOffsetUseCase;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.MarkStaleProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RequestMonitorCheckUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.DatabaseClockPort;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.UrlChecker;
import com.personal.baton.watch.application.monitoring.service.MarkStaleProjectionsService;
import com.personal.baton.watch.application.monitoring.service.PurgeAttemptHistoryService;
import com.personal.baton.watch.application.monitoring.service.RunDueChecksService;
import com.personal.baton.watch.application.monitoring.service.RequestMonitorCheckService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

@Configuration(proxyBeanMethods = false)
public class MonitoringConfiguration {

    @Bean
    JdbcMonitorPersistenceAdapter monitorPersistenceAdapter(
            JdbcClient jdbcClient, TransactionOperations transactions) {
        return new JdbcMonitorPersistenceAdapter(jdbcClient, transactions);
    }

    @Bean
    JdbcCheckWorkPersistenceAdapter checkWorkPersistenceAdapter(
            JdbcClient jdbcClient, TransactionOperations transactions) {
        return new JdbcCheckWorkPersistenceAdapter(jdbcClient, transactions);
    }

    @Bean
    @Primary
    CheckWorkPersistencePort meteredCheckWorkPersistence(
            JdbcCheckWorkPersistenceAdapter persistence,
            MonitoringMetrics metrics) {
        return new MeteredCheckWorkPersistence(persistence, metrics);
    }

    @Bean
    JdbcDatabaseClockAdapter databaseClockAdapter(JdbcClient jdbcClient) {
        return new JdbcDatabaseClockAdapter(jdbcClient);
    }

    @Bean
    GetDatabaseClockOffsetUseCase getDatabaseClockOffsetUseCase(
            DatabaseClockPort databaseClock,
            Clock clock) {
        return () -> {
            Instant before = clock.instant();
            Instant databaseTime = databaseClock.currentTime();
            Instant after = clock.instant();
            Instant midpoint = before.plus(Duration.between(before, after).dividedBy(2));
            return Duration.between(databaseTime, midpoint);
        };
    }

    @Bean
    GetCheckScheduleDelayUseCase getCheckScheduleDelayUseCase(CheckWorkPersistencePort persistence) {
        return persistence::getOldestDueCheckDelay;
    }

    @Bean
    SynchronizeMonitorUseCase synchronizeMonitorUseCase(MonitorPersistencePort persistence, Clock clock) {
        return command -> persistence.synchronize(command, clock.instant());
    }

    @Bean
    GetMonitorProjectionUseCase getMonitorProjectionUseCase(MonitorPersistencePort persistence) {
        return persistence::findProjection;
    }

    @Bean
    RequestMonitorCheckUseCase requestMonitorCheckUseCase(MonitorPersistencePort persistence, Clock clock) {
        return new RequestMonitorCheckService(persistence, clock);
    }

    @Bean
    RunDueChecksUseCase runDueChecksUseCase(
            CheckWorkPersistencePort persistence,
            UrlChecker checker,
            Clock clock,
            WatchProperties properties,
            DatabaseRuntimeProperties database,
            PersistenceProperties persistenceProperties) {
        WorkerExecutionBudget.requireSafe(
                "check",
                properties.workerExecutionBudget(),
                properties.leaseDuration(),
                properties.http().totalTimeout(),
                properties.checkBatchSize(),
                database,
                persistenceProperties);
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

    @Bean
    @Primary
    UrlChecker meteredUrlChecker(ApacheUrlChecker checker, MonitoringMetrics metrics) {
        return new MeteredUrlChecker(checker, metrics);
    }
}
