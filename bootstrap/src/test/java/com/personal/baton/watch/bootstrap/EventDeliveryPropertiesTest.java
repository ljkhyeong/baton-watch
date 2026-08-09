package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventDeliveryPropertiesTest {

    @Test
    void acceptsDisabledDeliveryWithoutCredentials() {
        assertDoesNotThrow(() -> properties(false, URI.create(""), "", Duration.ofSeconds(60), 10));
    }

    @Test
    void acceptsEnabledDeliveryWithBoundedHttpsConfiguration() {
        assertDoesNotThrow(() -> properties(
                true,
                URI.create("https://baton.example.com/api/v1/internal/resource-health-events"),
                "a-separate-delivery-token-longer-than-32-characters",
                Duration.ofSeconds(60),
                10));
    }

    @Test
    void rejectsInsecureOrCredentialedEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                URI.create("http://baton.example.com/events"),
                "a-separate-delivery-token-longer-than-32-characters",
                Duration.ofSeconds(60),
                10));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                URI.create("https://user@baton.example.com/events"),
                "a-separate-delivery-token-longer-than-32-characters",
                Duration.ofSeconds(60),
                10));
    }

    @Test
    void rejectsAConfigurationWhoseBatchCanOutliveTheLease() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                URI.create("https://baton.example.com/events"),
                "a-separate-delivery-token-longer-than-32-characters",
                Duration.ofSeconds(50),
                10));
    }

    @Test
    void rejectsReusingTheMonitorApiTokenForDelivery() {
        String sharedToken = "one-token-must-not-protect-both-directions";

        assertThrows(
                IllegalArgumentException.class,
                () -> EventDeliveryConfiguration.requireSeparateToken(sharedToken, sharedToken));
        assertDoesNotThrow(() -> EventDeliveryConfiguration.requireSeparateToken(
                sharedToken, "a-distinct-monitor-api-token-longer-than-32"));
        assertDoesNotThrow(() -> EventDeliveryConfiguration.requireSeparateToken(
                "????????????????????????????????", "éééééééééééééééééééééééééééééééé"));
    }

    @Test
    void enforcesBatchSizeHardCeilings() {
        assertDoesNotThrow(() -> properties(
                false,
                URI.create(""),
                "",
                Duration.ofMinutes(10),
                EventDeliveryProperties.MAX_DELIVERY_BATCH_SIZE,
                EventDeliveryProperties.MAX_MAINTENANCE_BATCH_SIZE));
        assertThrows(IllegalArgumentException.class, () -> properties(
                false,
                URI.create(""),
                "",
                Duration.ofMinutes(10),
                EventDeliveryProperties.MAX_DELIVERY_BATCH_SIZE + 1,
                100));
        assertThrows(IllegalArgumentException.class, () -> properties(
                false,
                URI.create(""),
                "",
                Duration.ofSeconds(60),
                10,
                EventDeliveryProperties.MAX_MAINTENANCE_BATCH_SIZE + 1));
    }

    @Test
    void enforcesTheRetryDelayHardCeiling() {
        assertDoesNotThrow(() -> properties(
                false,
                URI.create(""),
                "",
                Duration.ofMinutes(10),
                10,
                100,
                Duration.ofDays(30)));
        assertThrows(IllegalArgumentException.class, () -> properties(
                false,
                URI.create(""),
                "",
                Duration.ofMinutes(10),
                10,
                100,
                Duration.ofDays(30).plusNanos(1)));
    }

    @Test
    void rejectsOutboundResourceSettingsAboveTheirHardCeilings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> http(
                        OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES + 1,
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> http(
                        8_192,
                        OutboundResourceBounds.MAX_REQUEST_QUEUE_CAPACITY + 1));
    }

    private static EventDeliveryProperties properties(
            boolean enabled, URI endpoint, String token, Duration leaseDuration, int batchSize) {
        return properties(enabled, endpoint, token, leaseDuration, batchSize, 100);
    }

    private static EventDeliveryProperties properties(
            boolean enabled,
            URI endpoint,
            String token,
            Duration leaseDuration,
            int batchSize,
            int maintenanceBatchSize) {
        return properties(
                enabled,
                endpoint,
                token,
                leaseDuration,
                batchSize,
                maintenanceBatchSize,
                Duration.ofMinutes(15));
    }

    private static EventDeliveryProperties properties(
            boolean enabled,
            URI endpoint,
            String token,
            Duration leaseDuration,
            int batchSize,
            int maintenanceBatchSize,
            Duration maxRetryDelay) {
        return new EventDeliveryProperties(
                enabled,
                endpoint,
                token,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                leaseDuration,
                Duration.ofSeconds(5),
                maxRetryDelay,
                Duration.ofDays(30),
                batchSize,
                maintenanceBatchSize,
                http(8_192, 1));
    }

    private static EventDeliveryProperties.Http http(
            long maxResponseBytes, int requestQueueCapacity) {
        return new EventDeliveryProperties.Http(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                maxResponseBytes,
                100,
                8_192,
                2,
                8,
                1,
                requestQueueCapacity);
    }
}
