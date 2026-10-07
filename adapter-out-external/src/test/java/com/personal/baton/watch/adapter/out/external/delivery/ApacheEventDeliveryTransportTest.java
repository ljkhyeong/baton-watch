package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.http.LoopbackHttpTestServer;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApacheEventDeliveryTransportTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @AutoClose
    private final LoopbackHttpTestServer server = new LoopbackHttpTestServer();

    @Test
    void postsJsonWithBearerAndIdempotencyHeadersToThePinnedAddress() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> idempotencyKey = new AtomicReference<>();
        AtomicReference<String> acceptEncoding = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        server.handle("/callback", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        byte[] payload = "{\"eventId\":\"event-1\"}".getBytes(StandardCharsets.UTF_8);

        try (ApacheEventDeliveryTransport transport = transport(testLimits(8_192))) {
            DeliveryResponse response = execute(transport, payload, Duration.ofSeconds(2));

            assertEquals(204, response.statusCode());
        }
        assertEquals("POST", method.get());
        assertEquals("Bearer 0123456789abcdef0123456789abcdef", authorization.get());
        assertEquals("event-1", idempotencyKey.get());
        assertEquals("identity", acceptEncoding.get());
        assertTrue(contentType.get().startsWith("application/json"));
        assertArrayEquals(payload, receivedBody.get());
    }

    @ParameterizedTest
    @MethodSource("retryAfterResponses")
    void readsRetryAfterWithoutWaitingOrRetryingInTheClient(
            int statusCode, List<String> headerValues, Instant expectedRetryTime) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.handle("/callback", exchange -> {
            requests.incrementAndGet();
            headerValues.forEach(value -> exchange.getResponseHeaders().add("Retry-After", value));
            if (statusCode == 302) {
                // 리다이렉트를 따르면 같은 경로에 두 번째 요청이 도착한다.
                exchange.getResponseHeaders().add("Location", "/callback");
            }
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });

        try (var transport = transport(testLimits(8_192))) {
            DeliveryResponse response = execute(
                    transport, "{}".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));

            assertEquals(statusCode, response.statusCode());
            assertEquals(expectedRetryTime, response.retryNotBefore());
            assertEquals(1, requests.get());
        }
    }

    private static Stream<Arguments> retryAfterResponses() {
        return Stream.of(
                Arguments.of(429, List.of("120"), NOW.plusSeconds(120)),
                Arguments.of(503, List.of("120"), NOW.plusSeconds(120)),
                Arguments.of(503, List.of("Sat, 01 Aug 2026 00:03:00 GMT"), NOW.plusSeconds(180)),
                Arguments.of(503, List.of("Fri, 31 Jul 2026 23:59:00 GMT"), NOW.minusSeconds(60)),
                Arguments.of(503, List.of("0"), NOW),
                Arguments.of(503, List.of(), null),
                Arguments.of(503, List.of("not-a-date"), null),
                Arguments.of(503, List.of("-1"), null),
                Arguments.of(503, List.of("+10"), null),
                Arguments.of(503, List.of("1.5"), null),
                Arguments.of(503, List.of("9223372036854775807"), null),
                Arguments.of(503, List.of("999999999999999999999999"), null),
                Arguments.of(503, List.of("10", "20"), null),
                Arguments.of(503, List.of("10,20"), null),
                Arguments.of(204, List.of("120"), null),
                Arguments.of(302, List.of("120"), null),
                Arguments.of(500, List.of("120"), null));
    }

    @Test
    void discardsOnlyTheBoundedResponseBody() throws Exception {
        server.handle("/callback", exchange -> {
            byte[] body = "123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        try (ApacheEventDeliveryTransport transport = transport(testLimits(8))) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> execute(transport, "{}".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2)));

            assertEquals(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
        }
    }

    @Test
    void mapsAnOversizedResponseHeaderLineToResponseTooLarge() throws Exception {
        server.handle("/callback", exchange -> {
            exchange.getResponseHeaders().add("X-Oversized", "x".repeat(256));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        try (ApacheEventDeliveryTransport transport = transport(testLimits(8_192, 100, 128))) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> execute(transport, "{}".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2)));

            assertEquals(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
        }
    }

    @Test
    void cancelsAStreamingResponseAtTheDeadlineAndDeliversTheNextRequest() throws Exception {
        server.handleStreamThenQuick("/callback");
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        try (var transport = transport(testLimits(8_192))) {
            OutboundHttpFailure failure = assertThrows(OutboundHttpFailure.class,
                    () -> execute(transport, payload, Duration.ofSeconds(2)));

            assertEquals(OutboundHttpFailure.Kind.READ_TIMEOUT, failure.kind());
            assertEquals(204, execute(transport, payload, Duration.ofSeconds(1)).statusCode());
            assertTrue(server.awaitDisconnected());
        }
    }

    private ApacheEventDeliveryTransport transport(EventDeliveryLimits limits) {
        return new ApacheEventDeliveryTransport(
                server.uri("delivery.test", "/callback"), "0123456789abcdef0123456789abcdef", limits, 1, 1, CLOCK);
    }

    private static DeliveryResponse execute(
            ApacheEventDeliveryTransport transport, byte[] payload, Duration remainingTime)
            throws OutboundHttpFailure {
        return transport.execute(List.of(InetAddress.getLoopbackAddress()), payload, "event-1", remainingTime);
    }

    private static EventDeliveryLimits testLimits(long maxResponseBytes) {
        return testLimits(maxResponseBytes, 100, 8_192);
    }

    private static EventDeliveryLimits testLimits(
            long maxResponseBytes, int maxHeaderCount, int maxHeaderLineLength) {
        return new EventDeliveryLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                maxResponseBytes,
                maxHeaderCount,
                maxHeaderLineLength);
    }
}
