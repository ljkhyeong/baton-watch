package com.personal.baton.watch.adapter.out.external.delivery;

import static com.personal.baton.watch.adapter.out.external.delivery.EventDeliveryTestFixtures.DEFAULT_LIMITS;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void acceptsTheInclusiveBearerTokenLengthBoundariesAndUrlSafePunctuation() {
        try (ApacheHealthChangeEventSender ignored = sender(
                URI.create("https://events.example.com/callback"),
                "._~-" + "A".repeat(28))) {
            // 32자 하한과 허용 구두점을 함께 검증한다.
        }
        try (ApacheHealthChangeEventSender ignored = sender(
                URI.create("https://events.example.com/callback"),
                "A".repeat(200))) {
            // 200자 상한을 검증한다.
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA ",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAé"
    })
    void rejectsBearerTokensOutsideTheUrlSafeSyntax(String token) {
        assertThrows(
                IllegalArgumentException.class,
                () -> sender(URI.create("https://events.example.com/callback"), token));
    }

    @Test
    void rejectsBearerTokensAboveTheMaximumLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> sender(
                        URI.create("https://events.example.com/callback"),
                        "A".repeat(201)));
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
