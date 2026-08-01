package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ApacheHealthChangeEventSenderTest {

    @Test
    void validatesTheEndpointAndBearerTokenBeforeCreatingAProductionSender() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        URI.create("http://events.example.com/callback"),
                        "0123456789abcdef0123456789abcdef",
                        EventDeliveryLimits.DEFAULTS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        URI.create("https://127.0.0.1/callback"),
                        "0123456789abcdef0123456789abcdef",
                        EventDeliveryLimits.DEFAULTS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHealthChangeEventSender(
                        URI.create("https://events.example.com/callback"),
                        "unsafe token 0123456789abcdef0123456789abcdef",
                        EventDeliveryLimits.DEFAULTS));
    }

    @Test
    void closesOwnedBoundedExecutors() {
        assertDoesNotThrow(() -> {
            try (ApacheHealthChangeEventSender ignored = new ApacheHealthChangeEventSender(
                    URI.create("https://events.example.com/callback"),
                    "0123456789abcdef0123456789abcdef",
                    EventDeliveryLimits.DEFAULTS)) {
                // Construction and close are the lifecycle contract exercised here.
            }
        });
    }
}
