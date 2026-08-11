package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WatchPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsAConfigurationWhoseLeaseCoversTheBoundedBatch() {
        assertDoesNotThrow(() -> properties(Duration.ofSeconds(30), 2));
    }

    @Test
    void rejectsAConfigurationWhoseChecksCanOutliveTheLease() {
        assertThrows(IllegalArgumentException.class, () -> properties(Duration.ofSeconds(10), 2));
    }

    @Test
    void rejectsShortServiceTokens() {
        WatchProperties.Http http = http();
        assertThrows(IllegalArgumentException.class, () -> new WatchProperties(
                "too-short",
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                1,
                100,
                http));
    }

    @Test
    void enforcesWorkerBatchHardCeilings() {
        assertThat(validationFields(properties(
                Duration.ofMinutes(10),
                WatchProperties.MAX_CHECK_BATCH_SIZE,
                WatchProperties.MAX_MAINTENANCE_BATCH_SIZE))).isEmpty();
        assertThat(validationFields(properties(
                Duration.ofMinutes(10),
                WatchProperties.MAX_CHECK_BATCH_SIZE + 1,
                100))).containsExactly("checkBatchSize");
        assertThat(validationFields(properties(
                Duration.ofSeconds(30),
                1,
                WatchProperties.MAX_MAINTENANCE_BATCH_SIZE + 1)))
                .containsExactly("maintenanceBatchSize");
    }

    @Test
    void rejectsOutboundResourceSettingsAboveTheirHardCeilings() {
        assertThat(validationFields(http(
                OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES + 1, 8)))
                .containsExactly("maxResponseBytes");
        assertThat(validationFields(http(
                65_536, OutboundResourceBounds.MAX_DNS_QUEUE_CAPACITY + 1)))
                .containsExactly("dnsQueueCapacity");
    }

    private static WatchProperties properties(Duration leaseDuration, int batchSize) {
        return properties(leaseDuration, batchSize, 100);
    }

    private static WatchProperties properties(
            Duration leaseDuration, int batchSize, int maintenanceBatchSize) {
        return new WatchProperties(
                "a-test-token-that-is-longer-than-32-characters",
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                leaseDuration,
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                batchSize,
                maintenanceBatchSize,
                http());
    }

    private static WatchProperties.Http http() {
        return http(65_536, 8);
    }

    private static WatchProperties.Http http(
            long maxResponseBytes, int dnsQueueCapacity) {
        return new WatchProperties.Http(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                maxResponseBytes,
                3,
                100,
                8_192,
                2,
                dnsQueueCapacity,
                1,
                1);
    }

    private static List<String> validationFields(Object value) {
        return validator.validate(value).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .toList();
    }
}
