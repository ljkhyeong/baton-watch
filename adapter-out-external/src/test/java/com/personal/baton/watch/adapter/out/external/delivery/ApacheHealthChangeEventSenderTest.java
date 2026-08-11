package com.personal.baton.watch.adapter.out.external.delivery;

import static com.personal.baton.watch.adapter.out.external.delivery.EventDeliveryTestFixtures.DEFAULT_LIMITS;
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
                () -> sender(
                        URI.create("http://events.example.com/callback"),
                        "0123456789abcdef0123456789abcdef"));
        assertThrows(
                IllegalArgumentException.class,
                () -> sender(
                        URI.create("https://127.0.0.1/callback"),
                        "0123456789abcdef0123456789abcdef"));
        assertThrows(
                IllegalArgumentException.class,
                () -> sender(
                        URI.create("https://events.example.com/callback"),
                        "unsafe token 0123456789abcdef0123456789abcdef"));
    }

    @Test
    void closesOwnedBoundedExecutors() {
        assertDoesNotThrow(() -> {
            try (ApacheHealthChangeEventSender ignored = sender(
                    URI.create("https://events.example.com/callback"),
                    "0123456789abcdef0123456789abcdef")) {
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
                        endpoint, token, DEFAULT_LIMITS, 0, 8, 1, 1, objectMapper));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        endpoint, token, DEFAULT_LIMITS, 2, 8, 0, 1, objectMapper));
    }

    private static ApacheHealthChangeEventSender sender(URI endpoint, String bearerToken) {
        return new ApacheHealthChangeEventSender(
                endpoint,
                bearerToken,
                DEFAULT_LIMITS,
                2,
                8,
                1,
                1,
                new ObjectMapper());
    }
}
