package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

class ScheduledTaskObservationTest {

    @Test
    void recordsPropagatedWorkerFailuresWithSpringScheduledTaskMetrics() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        IllegalStateException failure = new IllegalStateException("sensitive callback detail");
        EventDeliveryScheduler scheduler = new EventDeliveryScheduler(
                () -> {
                    throw failure;
                },
                new MonitoringMetrics(meterRegistry));
        Method method = EventDeliveryScheduler.class.getDeclaredMethod("deliverPendingEvents");
        ScheduledMethodRunnable runnable = new ScheduledMethodRunnable(
                scheduler,
                method,
                WorkerSchedulingConfiguration.EVENT_DELIVERY_TASK_SCHEDULER,
                () -> observationRegistry);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, runnable::run);

        assertSame(failure, thrown);
        var failureTimer = meterRegistry.get("tasks.scheduled.execution")
                .tag("code.function", "deliverPendingEvents")
                .tag("code.namespace", EventDeliveryScheduler.class.getName())
                .tag("error", IllegalStateException.class.getSimpleName())
                .tag("exception", IllegalStateException.class.getSimpleName())
                .tag("outcome", "ERROR")
                .timer();
        assertEquals(1L, failureTimer.count());
        assertTrue(failureTimer.getId().getTags().stream()
                .noneMatch(tag -> tag.getValue().contains("sensitive callback detail")));
        assertTrue(meterRegistry.find("baton.watch.scheduler.failures").meters().isEmpty());
    }
}
