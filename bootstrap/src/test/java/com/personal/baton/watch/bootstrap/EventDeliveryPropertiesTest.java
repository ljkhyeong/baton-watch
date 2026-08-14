package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.application.monitoring.service.TimeBoundaryPolicy;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventDeliveryPropertiesTest {

    @Test
    void acceptsDisabledDeliveryWithoutCredentials() {
        assertDoesNotThrow(() -> properties(false, URI.create(""), "", Duration.ofSeconds(60), 10));
    }

    @Test
    void acceptsEnabledDeliveryWithConfiguredCredentials() {
        assertDoesNotThrow(() -> properties(
                true,
                URI.create("https://baton.example.com/api/v1/internal/resource-health-events"),
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
    void rejectsInstantOffsetsAboveTheApplicationHardCeiling() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                false,
                URI.create(""),
                "",
                TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET.plusNanos(1),
                1));
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
        return new EventDeliveryProperties(
                enabled,
                endpoint,
                token,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                leaseDuration,
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                batchSize,
                maintenanceBatchSize,
                http());
    }

    private static EventDeliveryProperties.Http http() {
        return new EventDeliveryProperties.Http(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                8_192,
                100,
                8_192,
                2,
                8,
                1,
                1);
    }
}
