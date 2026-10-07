package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DeliveryEndpointPolicyTest {

    private final DeliveryEndpointPolicy policy = new DeliveryEndpointPolicy();

    @Test
    void acceptsOnlyAnUnambiguousDefaultPortHttpsEndpoint() {
        URI implicit = URI.create("https://Events.Example.com/api/v1/health-events");
        URI explicit = URI.create("HTTPS://events.example.com:443/api/v1/health-events");

        assertSame(implicit, policy.validate(implicit));
        assertSame(explicit, policy.validate(explicit));
    }

    @ParameterizedTest
    @MethodSource("rejectedEndpoints")
    void rejectsUnsafeOrAmbiguousEndpoints(String rawEndpoint) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> policy.validate(URI.create(rawEndpoint)));

        assertEquals("event delivery endpoint violates policy", exception.getMessage());
    }

    private static Stream<String> rejectedEndpoints() {
        return Stream.of(
                "http://events.example.com/callback",
                "https://events.example.com/callback?token=secret",
                "https://127.0.0.1/callback",
                "https://events.example.com/%0aheader");
    }
}
