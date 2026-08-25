package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(OutputCaptureExtension.class)
class WorkerSchedulingConfigurationTest {

    private static final long AWAIT_SECONDS = 2;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(WorkerSchedulingConfiguration.class)
            .withPropertyValues(
                    "spring.task.scheduling.pool.size=4",
                    "spring.task.scheduling.shutdown.await-termination=true",
                    "spring.task.scheduling.shutdown.await-termination-period=65s");

    @Test
    void isolatesMonitoringDeliveryAndMaintenanceWhileKeepingEachSchedulerSingleThreaded() {
        contextRunner.run(context -> {
            Map<String, ThreadPoolTaskScheduler> schedulers = context.getBeansOfType(ThreadPoolTaskScheduler.class);
            assertThat(schedulers).containsOnlyKeys(
                    WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER,
                    WorkerSchedulingConfiguration.EVENT_DELIVERY_TASK_SCHEDULER,
                    WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER);
            assertThat(schedulers.values()).doesNotHaveDuplicates();
            assertThat(schedulers.values()).allMatch(scheduler ->
                    scheduler.getScheduledThreadPoolExecutor().getCorePoolSize() == 1);
            assertThat(schedulers.values()).allMatch(scheduler ->
                    scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy());
            assertThat(schedulers.values()).allMatch(scheduler ->
                    !scheduler.getScheduledThreadPoolExecutor()
                            .getExecuteExistingDelayedTasksAfterShutdownPolicy());

            ThreadPoolTaskScheduler delivery = schedulers.get(
                    WorkerSchedulingConfiguration.EVENT_DELIVERY_TASK_SCHEDULER);
            ThreadPoolTaskScheduler monitoring = schedulers.get(
                    WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER);
            ThreadPoolTaskScheduler maintenance = schedulers.get(
                    WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER);

            CountDownLatch deliveryStarted = new CountDownLatch(1);
            CountDownLatch releaseDelivery = new CountDownLatch(1);
            CountDownLatch queuedDeliveryCompleted = new CountDownLatch(1);
            CountDownLatch monitoringCompleted = new CountDownLatch(1);
            CountDownLatch maintenanceCompleted = new CountDownLatch(1);
            AtomicReference<String> deliveryThread = new AtomicReference<>();
            AtomicReference<String> monitoringThread = new AtomicReference<>();
            AtomicReference<String> maintenanceThread = new AtomicReference<>();

            try {
                delivery.execute(() -> {
                    deliveryThread.set(Thread.currentThread().getName());
                    deliveryStarted.countDown();
                    await(releaseDelivery);
                });
                assertThat(deliveryStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

                delivery.execute(queuedDeliveryCompleted::countDown);
                monitoring.execute(() -> {
                    monitoringThread.set(Thread.currentThread().getName());
                    monitoringCompleted.countDown();
                });
                maintenance.execute(() -> {
                    maintenanceThread.set(Thread.currentThread().getName());
                    maintenanceCompleted.countDown();
                });

                assertThat(monitoringCompleted.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(maintenanceCompleted.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(queuedDeliveryCompleted.getCount()).isEqualTo(1);
                assertThat(deliveryThread.get()).startsWith("watch-event-delivery-");
                assertThat(monitoringThread.get()).startsWith("watch-monitoring-");
                assertThat(maintenanceThread.get()).startsWith("watch-maintenance-");
            } finally {
                releaseDelivery.countDown();
            }
            assertThat(queuedDeliveryCompleted.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        });
    }

    @Test
    void redactsFailuresAndKeepsRepeatingTasksScheduled(CapturedOutput output) {
        contextRunner.run(context -> {
            ThreadPoolTaskScheduler scheduler = context.getBean(
                    WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER,
                    ThreadPoolTaskScheduler.class);
            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch twoRuns = new CountDownLatch(2);
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
                int attempt = attempts.incrementAndGet();
                twoRuns.countDown();
                if (attempt == 1) {
                    throw new IllegalStateException("sensitive scheduler detail");
                }
            }, Duration.ofMillis(10));

            try {
                assertThat(twoRuns.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } finally {
                future.cancel(false);
            }
        });

        assertThat(output)
                .contains("scheduled task failed failureType=IllegalStateException")
                .doesNotContain("sensitive scheduler detail");
    }

    @Test
    void routesEveryScheduledMethodToItsOwnedScheduler() throws NoSuchMethodException {
        assertScheduler(
                MonitoringScheduler.class.getDeclaredMethod("checkDueMonitors"),
                WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER);
        assertScheduler(
                MonitoringScheduler.class.getDeclaredMethod("markStaleProjections"),
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER);
        assertScheduler(
                MonitoringScheduler.class.getDeclaredMethod("purgeAttemptHistory"),
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER);
        assertScheduler(
                EventDeliveryScheduler.class.getDeclaredMethod("deliverPendingEvents"),
                WorkerSchedulingConfiguration.EVENT_DELIVERY_TASK_SCHEDULER);
        assertScheduler(
                EventDeliveryMaintenanceScheduler.class.getDeclaredMethod("purgeDeliveredEventHistory"),
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER);
        assertScheduler(
                EventDeliveryMaintenanceScheduler.class.getDeclaredMethod("refreshEventDeliveryBacklog"),
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER);
    }

    private static void assertScheduler(Method method, String expectedScheduler) {
        assertThat(method.getAnnotation(Scheduled.class).scheduler()).isEqualTo(expectedScheduler);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
