package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApacheEventDeliveryTransportTest {

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
                new ApacheEventDeliveryTransport(testLimits(8_192), 1, 1)) {
            int statusCode = transport.execute(
                    request("/callback", payload), Duration.ofSeconds(2));

            assertEquals(204, statusCode);
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
                new ApacheEventDeliveryTransport(testLimits(8_192), 1, 1)) {
            int statusCode = transport.execute(
                    request("/start", "{}".getBytes(StandardCharsets.UTF_8)),
                    Duration.ofSeconds(2));

            assertEquals(302, statusCode);
        }
        assertFalse(redirectedTargetCalled.get());
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
                new ApacheEventDeliveryTransport(testLimits(8), 1, 1)) {
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
                new ApacheEventDeliveryTransport(testLimits(8_192, 100, 128), 1, 1)) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> transport.execute(
                            request("/callback", "{}".getBytes(StandardCharsets.UTF_8)),
                            Duration.ofSeconds(2)));

            assertEquals(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
        }
    }

    private HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    }

    private ApprovedDeliveryRequest request(String path, byte[] payload) throws Exception {
        int port = server.getAddress().getPort();
        ValidatedDeliveryEndpoint endpoint = new ValidatedDeliveryEndpoint(
                URI.create("http://delivery.test:" + port + path), "delivery.test");
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
