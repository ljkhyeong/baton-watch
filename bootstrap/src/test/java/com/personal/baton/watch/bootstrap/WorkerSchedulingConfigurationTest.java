package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
    void routesEveryScheduledMethodToItsOwnedScheduler() {
        Map<String, String> expectedSchedulers = Map.of(
                "MonitoringScheduler#checkDueMonitors",
                WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER,
                "MonitoringMaintenanceScheduler#markStaleProjections",
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER,
                "MonitoringMaintenanceScheduler#purgeAttemptHistory",
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER,
                "MonitoringMaintenanceScheduler#refreshCheckScheduleDelay",
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER,
                "MonitoringMaintenanceScheduler#updateDatabaseClockOffset",
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER,
                "EventDeliveryScheduler#deliverPendingEvents",
                WorkerSchedulingConfiguration.EVENT_DELIVERY_TASK_SCHEDULER,
                "EventDeliveryMaintenanceScheduler#purgeDeliveredEventHistory",
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER,
                "EventDeliveryMaintenanceScheduler#refreshEventDeliveryBacklog",
                WorkerSchedulingConfiguration.MAINTENANCE_TASK_SCHEDULER);

        Map<String, String> actualSchedulers = List.of(
                        MonitoringScheduler.class,
                        MonitoringMaintenanceScheduler.class,
                        EventDeliveryScheduler.class,
                        EventDeliveryMaintenanceScheduler.class)
                .stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .collect(Collectors.toMap(
                        WorkerSchedulingConfigurationTest::methodKey,
                        method -> method.getAnnotation(Scheduled.class).scheduler()));

        assertThat(actualSchedulers).isEqualTo(expectedSchedulers);
    }

    private static String methodKey(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
