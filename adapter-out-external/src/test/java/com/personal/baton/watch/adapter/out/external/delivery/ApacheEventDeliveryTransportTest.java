package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.adapter.out.external.http.StreamingHttpTestServer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApacheEventDeliveryTransportTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsJsonWithBearerAndIdempotencyHeadersToThePinnedAddress() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> idempotencyKey = new AtomicReference<>();
        AtomicReference<String> acceptEncoding = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        server = server();
        server.createContext("/callback", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        byte[] payload = "{\"eventId\":\"event-1\"}".getBytes(StandardCharsets.UTF_8);

        try (ApacheEventDeliveryTransport transport =
                new ApacheEventDeliveryTransport(testLimits(8_192), 1, 1, CLOCK)) {
            DeliveryResponse response = transport.execute(
                    request("/callback", payload), Duration.ofSeconds(2));

            assertEquals(204, response.statusCode());
        }
        assertEquals("POST", method.get());
        assertEquals("Bearer 0123456789abcdef0123456789abcdef", authorization.get());
        assertEquals("event-1", idempotencyKey.get());
        assertEquals("identity", acceptEncoding.get());
        assertTrue(contentType.get().startsWith("application/json"));
        assertArrayEquals(payload, receivedBody.get());
    }

    @Test
    void returnsRedirectResponsesWithoutFollowingThem() throws Exception {
        AtomicBoolean redirectedTargetCalled = new AtomicBoolean();
        server = server();
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirectedTargetCalled.set(true);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try (ApacheEventDeliveryTransport transport =
                new ApacheEventDeliveryTransport(testLimits(8_192), 1, 1, CLOCK)) {
            DeliveryResponse response = transport.execute(
                    request("/start", "{}".getBytes(StandardCharsets.UTF_8)),
                    Duration.ofSeconds(2));

            assertEquals(302, response.statusCode());
        }
        assertFalse(redirectedTargetCalled.get());
    }

    @ParameterizedTest
    @MethodSource("retryAfterResponses")
    void readsRetryAfterWithoutWaitingOrRetryingInTheClient(
            int statusCode, List<String> headerValues, Instant expectedRetryTime) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server();
        server.createContext("/callback", exchange -> {
            requests.incrementAndGet();
            headerValues.forEach(value -> exchange.getResponseHeaders().add("Retry-After", value));
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();

        try (var transport = new ApacheEventDeliveryTransport(testLimits(8_192), 1, 1, CLOCK)) {
            DeliveryResponse response = transport.execute(
                    request("/callback", "{}".getBytes(StandardCharsets.UTF_8)), Duration.ofSeconds(2));

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
        server = server();
        server.createContext("/callback", exchange -> {
            byte[] body = "123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try (ApacheEventDeliveryTransport transport =
                new ApacheEventDeliveryTransport(testLimits(8), 1, 1, CLOCK)) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> transport.execute(
                            request("/callback", "{}".getBytes(StandardCharsets.UTF_8)),
                            Duration.ofSeconds(2)));

            assertEquals(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
        }
    }

    @Test
    void mapsAnOversizedResponseHeaderLineToResponseTooLarge() throws Exception {
        server = server();
        server.createContext("/callback", exchange -> {
            exchange.getResponseHeaders().add("X-Oversized", "x".repeat(256));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try (ApacheEventDeliveryTransport transport =
                new ApacheEventDeliveryTransport(testLimits(8_192, 100, 128), 1, 1, CLOCK)) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> transport.execute(
                            request("/callback", "{}".getBytes(StandardCharsets.UTF_8)),
                            Duration.ofSeconds(2)));

            assertEquals(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
        }
    }

    @Test
    void cancelsAStreamingResponseAtTheDeadlineAndDeliversTheNextRequest() throws Exception {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        try (var streaming = new StreamingHttpTestServer();
                var transport = new ApacheEventDeliveryTransport(testLimits(8_192), 1, 1, CLOCK)) {
            OutboundHttpFailure failure = assertThrows(OutboundHttpFailure.class,
                    () -> transport.execute(request(streaming.uri("delivery.test", "/stream"), payload),
                            Duration.ofSeconds(2)));

            assertEquals(OutboundHttpFailure.Kind.READ_TIMEOUT, failure.kind());
            assertEquals(204, transport.execute(
                    request(streaming.uri("delivery.test", "/quick"), payload), Duration.ofSeconds(1)).statusCode());
            assertTrue(streaming.awaitDisconnected());
        }
    }

    private HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    }

    private ApprovedDeliveryRequest request(String path, byte[] payload) throws Exception {
        int port = server.getAddress().getPort();
        return request(URI.create("http://delivery.test:" + port + path), payload);
    }

    private static ApprovedDeliveryRequest request(URI uri, byte[] payload) {
        ValidatedDeliveryEndpoint endpoint = new ValidatedDeliveryEndpoint(
                uri, "delivery.test");
        return new ApprovedDeliveryRequest(
                endpoint,
                List.of(InetAddress.getLoopbackAddress()),
                payload,
                "0123456789abcdef0123456789abcdef",
                "event-1");
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
