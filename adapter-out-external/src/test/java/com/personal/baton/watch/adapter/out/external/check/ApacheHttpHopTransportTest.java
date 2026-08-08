package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApacheHttpHopTransportTest {

    private static final long ASYNC_COORDINATION_TIMEOUT_SECONDS = 5;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getsThePinnedAddressWithoutFollowingRedirectsAndPreservesResponseMetadata()
            throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> host = new AtomicReference<>();
        AtomicReference<String> connection = new AtomicReference<>();
        AtomicBoolean redirectTargetCalled = new AtomicBoolean();
        server = server();
        server.createContext("/start", exchange -> {
            method.set(exchange.getRequestMethod());
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            connection.set(exchange.getRequestHeaders().getFirst("Connection"));
            exchange.getResponseHeaders().add("Location", "/first");
            exchange.getResponseHeaders().add("Location", "/second");
            byte[] body = "redirect".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/first", exchange -> {
            redirectTargetCalled.set(true);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        try (ApacheHttpHopTransport transport =
                new ApacheHttpHopTransport(testLimits(64), 1, 1)) {
            HttpHopResponse response = transport.execute(
                    target("/start"), Duration.ofSeconds(2), 64);

            assertEquals(302, response.statusCode());
            assertEquals(List.of("/first", "/second"), response.locations());
            assertEquals(8, response.responseBytes());
        }
        assertEquals("GET", method.get());
        assertEquals("check.test:" + port, host.get());
        assertEquals("close", connection.get());
        assertFalse(redirectTargetCalled.get());
    }

    @Test
    void reportsConsumedBytesWhenAStreamingResponseExceedsTheRemainingBudget()
            throws Exception {
        server = server();
        server.createContext("/body", exchange -> {
            byte[] body = "123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try (ApacheHttpHopTransport transport =
                new ApacheHttpHopTransport(testLimits(64), 1, 1)) {
            TransportFailure failure = assertThrows(
                    TransportFailure.class,
                    () -> transport.execute(target("/body"), Duration.ofSeconds(2), 8));

            assertEquals(TransportFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
            assertEquals(8, failure.responseBytes());
        }
    }

    @Test
    void abortsAnUnknownLengthResponseAtTheCapWithoutWaitingForMoreBodyBytes()
            throws Exception {
        CountDownLatch prefixSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server = server();
        server.createContext("/body", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write("12345678".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                prefixSent.countDown();
                await(releaseBody);
            } finally {
                exchange.close();
            }
        });
        server.start();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<TransportFailure> result = null;

        try (ApacheHttpHopTransport transport =
                new ApacheHttpHopTransport(testLimits(64), 1, 1)) {
            result = caller.submit(() -> {
                try {
                    transport.execute(target("/body"), Duration.ofSeconds(2), 8);
                    return null;
                } catch (TransportFailure failure) {
                    return failure;
                }
            });

            assertTrue(prefixSent.await(
                    ASYNC_COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            TransportFailure failure = result.get(
                    ASYNC_COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(TransportFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
            assertEquals(8, failure.responseBytes());
        } finally {
            releaseBody.countDown();
            if (result != null && !result.isDone()) {
                result.cancel(true);
            }
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void mapsExcessiveResponseHeaderCountToResponseTooLarge() throws Exception {
        server = server();
        server.createContext("/headers", exchange -> {
            for (int index = 0; index < 16; index++) {
                exchange.getResponseHeaders().add("X-Test-" + index, "value");
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try (ApacheHttpHopTransport transport =
                new ApacheHttpHopTransport(testLimits(64, 4, 8_192), 1, 1)) {
            TransportFailure failure = assertThrows(
                    TransportFailure.class,
                    () -> transport.execute(target("/headers"), Duration.ofSeconds(2), 64));

            assertEquals(TransportFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
            assertEquals(0, failure.responseBytes());
        }
    }

    @Test
    void rejectsDisabledExecutorBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHttpHopTransport(testLimits(64), 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHttpHopTransport(testLimits(64), 1, 0));
    }

    private HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    }

    private ApprovedTarget target(String path) throws Exception {
        int port = server.getAddress().getPort();
        URI uri = URI.create("http://check.test:" + port + path);
        ValidatedUri validated = new ValidatedUri(uri, "http", "check.test", uri.toString());
        return new ApprovedTarget(validated, List.of(InetAddress.getLoopbackAddress()));
    }

    private static CheckerLimits testLimits(long maxResponseBytes) {
        return testLimits(maxResponseBytes, 100, 8_192);
    }

    private static CheckerLimits testLimits(
            long maxResponseBytes, int maxHeaderCount, int maxHeaderLineLength) {
        return new CheckerLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                maxResponseBytes,
                3,
                maxHeaderCount,
                maxHeaderLineLength);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
