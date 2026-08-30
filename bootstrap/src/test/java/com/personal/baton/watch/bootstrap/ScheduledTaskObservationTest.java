package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ScheduledTasksObservationAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(OutputCaptureExtension.class)
class ScheduledTaskObservationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ScheduledTasksObservationAutoConfiguration.class))
            .withUserConfiguration(
                    WorkerSchedulingConfiguration.class,
                    ScheduledTaskTestConfiguration.class);

    @Test
    void observesAndRedactsARealRepeatedScheduledFailure(CapturedOutput output) {
        contextRunner.run(context -> {
            FailingScheduledWorker worker = context.getBean(FailingScheduledWorker.class);
            assertThat(worker.twoRuns.await(2, TimeUnit.SECONDS)).isTrue();

            var registry = context.getBean(PrometheusMeterRegistry.class);
            var failureTimer = registry
                    .get("tasks.scheduled.execution")
                    .tag("code.function", "run")
                    .tag("code.namespace", FailingScheduledWorker.class.getCanonicalName())
                    .tag("error", IllegalStateException.class.getSimpleName())
                    .tag("exception", IllegalStateException.class.getSimpleName())
                    .tag("outcome", "ERROR")
                    .timer();
            assertThat(failureTimer.count()).isOne();
            assertThat(failureTimer.getId().getTags())
                    .noneMatch(tag -> tag.getValue().contains("sensitive scheduler detail"));
            assertThat(registry.scrape()).contains(
                    "tasks_scheduled_execution_seconds_count{",
                    "code_namespace=\"" + FailingScheduledWorker.class.getCanonicalName() + "\"",
                    "outcome=\"ERROR\"");
        });

        assertThat(output)
                .contains("scheduled task failed failureType=IllegalStateException")
                .doesNotContain("sensitive scheduler detail");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class ScheduledTaskTestConfiguration {

        @Bean
        ThreadPoolTaskSchedulerBuilder taskSchedulerBuilder() {
            return new ThreadPoolTaskSchedulerBuilder();
        }

        @Bean
        PrometheusMeterRegistry meterRegistry() {
            return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        }

        @Bean
        ObservationRegistry observationRegistry(PrometheusMeterRegistry meterRegistry) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig()
                    .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
            return registry;
        }

        @Bean
        FailingScheduledWorker failingScheduledWorker() {
            return new FailingScheduledWorker();
        }
    }

    static final class FailingScheduledWorker {

        private final CountDownLatch twoRuns = new CountDownLatch(2);
        private final AtomicInteger attempts = new AtomicInteger();

        @Scheduled(
                fixedDelay = 10,
                scheduler = WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER)
        void run() {
            int attempt = attempts.incrementAndGet();
            twoRuns.countDown();
            if (attempt == 1) {
                throw new IllegalStateException("sensitive scheduler detail");
            }
        }
    }
}
