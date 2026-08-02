package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApacheHealthChangeEventSenderTest {

    @Test
    void validatesTheEndpointAndBearerTokenBeforeCreatingAProductionSender() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        URI.create("http://events.example.com/callback"),
                        "0123456789abcdef0123456789abcdef",
                        EventDeliveryLimits.DEFAULTS,
                        new ObjectMapper()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        URI.create("https://127.0.0.1/callback"),
                        "0123456789abcdef0123456789abcdef",
                        EventDeliveryLimits.DEFAULTS,
                        new ObjectMapper()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        URI.create("https://events.example.com/callback"),
                        "unsafe token 0123456789abcdef0123456789abcdef",
                        EventDeliveryLimits.DEFAULTS,
                        new ObjectMapper()));
    }

    @Test
    void closesOwnedBoundedExecutors() {
        assertDoesNotThrow(() -> {
            try (ApacheHealthChangeEventSender ignored = new ApacheHealthChangeEventSender(
                    URI.create("https://events.example.com/callback"),
                    "0123456789abcdef0123456789abcdef",
                    EventDeliveryLimits.DEFAULTS,
                    new ObjectMapper())) {
                // Construction and close are the lifecycle contract exercised here.
            }
        });
    }

    @Test
    void rejectsAllExecutorBoundsBeforeCreatingOwnedResources() {
        URI endpoint = URI.create("https://events.example.com/callback");
        String token = "0123456789abcdef0123456789abcdef";
        ObjectMapper objectMapper = new ObjectMapper();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        endpoint, token, EventDeliveryLimits.DEFAULTS, 0, 8, 1, 1, objectMapper));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        endpoint, token, EventDeliveryLimits.DEFAULTS, 2, 8, 0, 1, objectMapper));
    }
}
