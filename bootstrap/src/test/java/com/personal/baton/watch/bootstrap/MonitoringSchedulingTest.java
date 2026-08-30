package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.port.in.GetDatabaseClockOffsetUseCase;
import com.personal.baton.watch.application.monitoring.port.in.GetEventDeliveryBacklogUseCase;
import com.personal.baton.watch.application.monitoring.port.in.MarkStaleProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeDeliveredEventsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class MonitoringSchedulingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(
                    Scheduling.class, WorkerSchedulingConfiguration.class,
                    MonitoringScheduler.class, MonitoringMaintenanceScheduler.class,
                    EventDeliveryScheduler.class, EventDeliveryMaintenanceScheduler.class)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(MonitoringMetrics.class)
            .withBean(RunDueChecksUseCase.class,
                    () -> () -> new DueCheckBatchResult(0, 0, 0, 0, Duration.ZERO))
            .withBean(RunEventDeliveriesUseCase.class,
                    () -> () -> new EventDeliveryBatchResult(0, 0, 0, 0, 0))
            .withBean(MarkStaleProjectionsUseCase.class, () -> () -> 0)
            .withBean(PurgeAttemptHistoryUseCase.class, () -> () -> 0)
            .withBean(PurgeDeliveredEventsUseCase.class, () -> () -> 0)
            .withBean(GetDatabaseClockOffsetUseCase.class, () -> () -> Duration.ZERO)
            .withBean(GetEventDeliveryBacklogUseCase.class,
                    () -> () -> new EventDeliveryBacklog(0, Optional.empty()))
            .withPropertyValues(
                    "watch.api-token=a-test-token-that-is-longer-than-32-characters",
                    "watch.poll-interval=1h", "watch.maintenance-interval=1h",
                    "watch.event-delivery.enabled=true",
                    "watch.event-delivery.poll-interval=1h",
                    "watch.event-delivery.maintenance-interval=1h");

    @ParameterizedTest(name = "점검 설정 {0}에서 전달과 유지보수 예약 유지")
    @CsvSource({
            "default,true", "true,true", "false,false", "on,true", "yes,true", "1,true",
            "' true ',true", "off,false", "no,false", "0,false", "' false ',false"
    })
    void keepsDeliveryAndMaintenanceScheduledWhenChecksAreDisabled(String enabled, boolean expected) {
        ApplicationContextRunner configured = enabled.equals("default")
                ? runner : runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("checkSetting", Map.of("watch.check-enabled", enabled))));
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WatchProperties.class).checkEnabled()).isEqualTo(expected);
            assertThat(context).hasSingleBean(MonitoringMaintenanceScheduler.class)
                    .hasSingleBean(EventDeliveryScheduler.class)
                    .hasSingleBean(EventDeliveryMaintenanceScheduler.class);
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                    .getScheduledTasks();
            if (!expected) {
                assertThat(context).doesNotHaveBean(MonitoringScheduler.class);
                assertThat(tasks).hasSize(6);
            } else {
                assertThat(context).hasSingleBean(MonitoringScheduler.class);
                assertThat(tasks).hasSize(7);
            }
        });
    }

    @Test
    void rejectsInvalidCheckSettingInsteadOfSilentlyDisablingChecks() {
        runner.withPropertyValues("watch.check-enabled=enabled")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(WatchProperties.class)
    static class Scheduling {
    }
}
