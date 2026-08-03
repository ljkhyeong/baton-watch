package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcCheckWorkPersistenceAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcMonitorPersistenceAdapter;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.MarkStaleProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

class MonitoringConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MonitoringConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(TransactionOperations.class, () -> mock(TransactionOperations.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(WatchProperties.class, MonitoringConfigurationTest::watchProperties);

    @Test
    void createsSeparatePersistenceAdaptersAndAllMonitoringUseCases() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MonitorPersistencePort.class);
            assertThat(context).hasSingleBean(CheckWorkPersistencePort.class);

            MonitorPersistencePort monitorPersistence = context.getBean(MonitorPersistencePort.class);
            CheckWorkPersistencePort checkWorkPersistence = context.getBean(CheckWorkPersistencePort.class);

            assertThat(monitorPersistence).isInstanceOf(JdbcMonitorPersistenceAdapter.class);
            assertThat(checkWorkPersistence).isInstanceOf(JdbcCheckWorkPersistenceAdapter.class);
            assertThat(monitorPersistence).isNotSameAs(checkWorkPersistence);

            assertThat(context).hasSingleBean(SynchronizeMonitorUseCase.class);
            assertThat(context).hasSingleBean(GetMonitorProjectionUseCase.class);
            assertThat(context).hasSingleBean(RunDueChecksUseCase.class);
            assertThat(context).hasSingleBean(MarkStaleProjectionsUseCase.class);
            assertThat(context).hasSingleBean(PurgeAttemptHistoryUseCase.class);
        });
    }

    private static WatchProperties watchProperties() {
        return new WatchProperties(
                "a-test-token-that-is-longer-than-32-characters",
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                1,
                100,
                new WatchProperties.Http(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        65_536,
                        3,
                        100,
                        8_192,
                        2,
                        8,
                        1,
                        1));
    }
}
