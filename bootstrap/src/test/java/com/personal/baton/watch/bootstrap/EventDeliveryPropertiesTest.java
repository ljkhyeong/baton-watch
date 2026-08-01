package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static EventDeliveryProperties properties(
            boolean enabled, URI endpoint, String token, Duration leaseDuration, int batchSize) {
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
                100,
                new EventDeliveryProperties.Http(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        8_192,
                        100,
                        8_192,
                        2,
                        8,
                        1,
                        1));
    }
}
