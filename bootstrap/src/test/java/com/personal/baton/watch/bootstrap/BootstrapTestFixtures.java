package com.personal.baton.watch.bootstrap;

import java.net.URI;
import java.time.Duration;

final class BootstrapTestFixtures {

    private BootstrapTestFixtures() {
    }

    static WatchProperties watchProperties() {
        return new WatchProperties(
                "a-test-token-that-is-longer-than-32-characters",
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                1,
                100,
                new WatchProperties.Http(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        65_536,
                        3,
                        100,
                        8_192,
                        2,
                        8,
                        1,
                        1));
    }

    static EventDeliveryProperties enabledEventDeliveryProperties() {
        return enabledEventDeliveryProperties(
                "a-separate-delivery-token-longer-than-32-characters");
    }

    static EventDeliveryProperties enabledEventDeliveryProperties(String token) {
        return eventDeliveryProperties(
                true,
                URI.create("https://baton.example.com/api/v1/internal/resource-health-events"),
                token);
    }

    static EventDeliveryProperties disabledEventDeliveryProperties() {
        return eventDeliveryProperties(false, URI.create(""), "");
    }

    private static EventDeliveryProperties eventDeliveryProperties(
            boolean enabled, URI endpoint, String token) {
        return new EventDeliveryProperties(
                enabled,
                endpoint,
                token,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                10,
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
