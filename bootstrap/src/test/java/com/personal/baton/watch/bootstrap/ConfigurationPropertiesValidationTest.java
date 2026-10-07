package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
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
                .run(context -> assertInvalidFields(
                        context.getStartupFailure(),
                        "checkBatchSize",
                        "http.dnsQueueCapacity",
                        "leaseDuration",
                        "pollInterval"));
    }

    @Test
    void rejectsInvalidDeliveryWorkerAndNestedHttpBoundsDuringBinding() {
        eventDeliveryContext(
                        "watch.event-delivery.batch-size=101",
                        "watch.event-delivery.http.request-queue-capacity=17",
                        "watch.event-delivery.lease-duration=0s",
                        "watch.event-delivery.retention=366d")
                .run(context -> assertInvalidFields(
                        context.getStartupFailure(),
                        "batchSize",
                        "http.requestQueueCapacity",
                        "leaseDuration",
                        "retention"));
    }

    @Test
    void rejectsWatchPeriodsBelowOperationalMinimums() {
        watchContext(
                        "watch.poll-interval=999ms",
                        "watch.maintenance-interval=59s",
                        "watch.check-interval=59s",
                        "watch.internal-failure-retry-interval=29s")
                .run(context -> assertInvalidFields(
                        context.getStartupFailure(),
                        "pollInterval",
                        "maintenanceInterval",
                        "checkInterval",
                        "internalFailureRetryInterval"));
    }

    @Test
    void rejectsDeliveryPeriodsBelowOperationalMinimums() {
        eventDeliveryContext(
                        "watch.event-delivery.poll-interval=999ms",
                        "watch.event-delivery.initial-retry-delay=4s",
                        "watch.event-delivery.max-retry-delay=4s")
                .run(context -> assertInvalidFields(
                        context.getStartupFailure(),
                        "pollInterval",
                        "initialRetryDelay",
                        "maxRetryDelay"));
    }

    private static ApplicationContextRunner watchContext(String... invalidProperties) {
        return productionDefaults(WatchConfiguration.class)
                .withPropertyValues("watch.api-token=a-test-token-that-is-longer-than-32-characters")
                .withPropertyValues(invalidProperties);
    }

    private static ApplicationContextRunner eventDeliveryContext(String... invalidProperties) {
        return productionDefaults(EventDeliveryConfigurationProperties.class).withPropertyValues(invalidProperties);
    }

    private static ApplicationContextRunner productionDefaults(Class<?> configuration) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(configuration);
    }

    private static void assertInvalidFields(Throwable failure, String... expectedFields) {
        assertThat(failure).rootCause()
                .isInstanceOfSatisfying(BindValidationException.class, validationFailure ->
                        assertThat(validationFailure.getValidationErrors().getAllErrors().stream()
                                        .filter(FieldError.class::isInstance)
                                        .map(FieldError.class::cast)
                                        .map(FieldError::getField))
                                .containsExactlyInAnyOrder(expectedFields));
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
