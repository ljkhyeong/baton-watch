package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.FieldError;

class ConfigurationPropertiesValidationTest {

    @Test
    void rejectsInvalidWatchWorkerAndNestedHttpBoundsDuringBinding() {
        watchContext(
                        "watch.check-batch-size=101",
                        "watch.http.dns-queue-capacity=65",
                        "watch.poll-interval=0s",
                        "watch.lease-duration=366d")
                .run(context -> {
                    BindValidationException failure = findValidationFailure(context.getStartupFailure());
                    assertThat(fieldNames(failure))
                            .containsExactlyInAnyOrder(
                                    "checkBatchSize",
                                    "http.dnsQueueCapacity",
                                    "leaseDuration",
                                    "pollInterval");
                });
    }

    @Test
    void rejectsInvalidDeliveryWorkerAndNestedHttpBoundsDuringBinding() {
        eventDeliveryContext(
                        "watch.event-delivery.batch-size=101",
                        "watch.event-delivery.http.request-queue-capacity=17",
                        "watch.event-delivery.maintenance-interval=0s",
                        "watch.event-delivery.retention=366d")
                .run(context -> {
                    BindValidationException failure = findValidationFailure(context.getStartupFailure());
                    assertThat(fieldNames(failure))
                            .containsExactlyInAnyOrder(
                                    "batchSize",
                                    "http.requestQueueCapacity",
                                    "maintenanceInterval",
                                    "retention");
                });
    }

    @Test
    void rejectsWatchPeriodsBelowOperationalMinimums() {
        watchContext(
                        "watch.poll-interval=999ms",
                        "watch.maintenance-interval=59s",
                        "watch.check-interval=59s",
                        "watch.internal-failure-retry-interval=29s")
                .run(context -> {
                    BindValidationException failure = findValidationFailure(context.getStartupFailure());
                    assertThat(fieldNames(failure))
                            .containsExactlyInAnyOrder(
                                    "pollInterval",
                                    "maintenanceInterval",
                                    "checkInterval",
                                    "internalFailureRetryInterval");
                });
    }

    @Test
    void rejectsDeliveryPeriodsBelowOperationalMinimums() {
        eventDeliveryContext(
                        "watch.event-delivery.poll-interval=999ms",
                        "watch.event-delivery.maintenance-interval=59s",
                        "watch.event-delivery.initial-retry-delay=4s",
                        "watch.event-delivery.max-retry-delay=4s")
                .run(context -> {
                    BindValidationException failure = findValidationFailure(context.getStartupFailure());
                    assertThat(fieldNames(failure))
                            .containsExactlyInAnyOrder(
                                    "pollInterval",
                                    "maintenanceInterval",
                                    "initialRetryDelay",
                                    "maxRetryDelay");
                });
    }

    private static ApplicationContextRunner watchContext(String... invalidProperties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(WatchConfiguration.class)
                .withPropertyValues(
                        "watch.api-token=a-test-token-that-is-longer-than-32-characters",
                        "watch.poll-interval=1s",
                        "watch.maintenance-interval=1m",
                        "watch.worker-execution-budget=60s",
                        "watch.lease-duration=10m",
                        "watch.check-interval=1m",
                        "watch.internal-failure-retry-interval=30s",
                        "watch.stale-after=10m",
                        "watch.retention=30d",
                        "watch.check-batch-size=1",
                        "watch.maintenance-batch-size=100",
                        "watch.http.connect-timeout=2s",
                        "watch.http.response-timeout=3s",
                        "watch.http.total-timeout=5s",
                        "watch.http.max-response-bytes=65536",
                        "watch.http.max-redirects=3",
                        "watch.http.max-header-count=100",
                        "watch.http.max-header-line-length=8192",
                        "watch.http.dns-threads=2",
                        "watch.http.dns-queue-capacity=8",
                        "watch.http.request-threads=1",
                        "watch.http.request-queue-capacity=1")
                .withPropertyValues(invalidProperties);
    }

    private static ApplicationContextRunner eventDeliveryContext(String... invalidProperties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(EventDeliveryConfigurationProperties.class)
                .withPropertyValues(
                        "watch.event-delivery.enabled=false",
                        "watch.event-delivery.endpoint=",
                        "watch.event-delivery.bearer-token=",
                        "watch.event-delivery.poll-interval=1s",
                        "watch.event-delivery.maintenance-interval=1m",
                        "watch.event-delivery.lease-duration=10m",
                        "watch.event-delivery.initial-retry-delay=5s",
                        "watch.event-delivery.max-retry-delay=15m",
                        "watch.event-delivery.retention=30d",
                        "watch.event-delivery.batch-size=10",
                        "watch.event-delivery.maintenance-batch-size=100",
                        "watch.event-delivery.http.connect-timeout=2s",
                        "watch.event-delivery.http.response-timeout=3s",
                        "watch.event-delivery.http.total-timeout=5s",
                        "watch.event-delivery.http.max-response-bytes=8192",
                        "watch.event-delivery.http.max-header-count=100",
                        "watch.event-delivery.http.max-header-line-length=8192",
                        "watch.event-delivery.http.dns-threads=2",
                        "watch.event-delivery.http.dns-queue-capacity=8",
                        "watch.event-delivery.http.request-threads=1",
                        "watch.event-delivery.http.request-queue-capacity=1")
                .withPropertyValues(invalidProperties);
    }

    private static BindValidationException findValidationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof BindValidationException validationFailure) {
                return validationFailure;
            }
            current = current.getCause();
        }
        throw new AssertionError("BindValidationException was not found", failure);
    }

    private static List<String> fieldNames(BindValidationException failure) {
        return failure.getValidationErrors().getAllErrors().stream()
                .filter(FieldError.class::isInstance)
                .map(FieldError.class::cast)
                .map(FieldError::getField)
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WatchProperties.class)
    static class WatchConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EventDeliveryProperties.class)
    static class EventDeliveryConfigurationProperties {
    }

}
