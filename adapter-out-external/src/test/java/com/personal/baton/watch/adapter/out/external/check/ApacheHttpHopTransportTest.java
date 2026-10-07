package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.http.LoopbackHttpTestServer;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApacheHttpHopTransportTest {

    @AutoClose
    private final LoopbackHttpTestServer server = new LoopbackHttpTestServer();

    @Test
    void getsThePinnedAddressWithoutFollowingRedirectsAndPreservesResponseMetadata()
            throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> host = new AtomicReference<>();
        AtomicBoolean redirectTargetCalled = new AtomicBoolean();
        server.handle("/start", exchange -> {
            method.set(exchange.getRequestMethod());
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.getResponseHeaders().add("Location", "/first");
            exchange.getResponseHeaders().add("Location", "/second");
            byte[] body = "redirect".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.handle("/first", exchange -> {
            redirectTargetCalled.set(true);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        ApprovedTarget start = target("/start");
        try (ApacheHttpHopTransport transport =
                new ApacheHttpHopTransport(testLimits(), 1, 1)) {
            HttpHopResponse response = transport.execute(start, Duration.ofSeconds(2));

            assertEquals(302, response.statusCode());
            assertEquals(List.of("/first", "/second"), response.locations());
        }
        assertEquals("GET", method.get());
        assertEquals(start.target().uri().getAuthority(), host.get());
        assertFalse(redirectTargetCalled.get());
    }

    @Test
    void preservesRepeatedSlashesInTheRequestPath() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        server.handle("/docs", exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        try (var transport = new ApacheHttpHopTransport(testLimits(), 1, 1)) {
            assertEquals(204, transport.execute(target("/docs//page"), Duration.ofSeconds(2)).statusCode());
        }
        assertEquals("/docs//page", path.get());
    }

    @Test
    void pinsAHostThatStartsWithAPunycodeLabel() throws Exception {
        AtomicReference<String> host = new AtomicReference<>();
        server.handle("/idn", exchange -> {
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        ApprovedTarget idn = target("xn--bcher-kva.test", "/idn");
        try (var transport = new ApacheHttpHopTransport(testLimits(), 1, 1)) {
            assertEquals(204, transport.execute(idn, Duration.ofSeconds(2)).statusCode());
        }
        assertEquals(idn.target().uri().getAuthority(), host.get());
    }

    @Test
    void acceptsALargeDeclaredBodyWithoutWaitingForIt() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.handle("/large", exchange -> {
            exchange.sendResponseHeaders(200, 1024 * 1024);
            try {
                exchange.getResponseBody().flush();
                await(releaseBody);
            } finally {
                exchange.close();
            }
        });

        try (var transport = new ApacheHttpHopTransport(testLimits(), 1, 1)) {
            HttpHopResponse response = transport.execute(target("/large"), Duration.ofSeconds(1));
            assertEquals(200, response.statusCode());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void closesAStreamingBodyImmediatelyAndReleasesTheWorker() throws Exception {
        try (var transport = new ApacheHttpHopTransport(testLimits(), 1, 1)) {
            HttpHopResponse response = transport.execute(target("/stream"), Duration.ofSeconds(1));
            assertEquals(200, response.statusCode());
            assertTrue(server.awaitDisconnected());
            assertEquals(204, transport.execute(target("/quick"), Duration.ofSeconds(1)).statusCode());
        }
    }

    @Test
    void mapsExcessiveResponseHeaderCountToResponseTooLarge() throws Exception {
        server.handle("/headers", exchange -> {
            for (int index = 0; index < 16; index++) {
                exchange.getResponseHeaders().add("X-Test-" + index, "value");
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        try (ApacheHttpHopTransport transport =
                new ApacheHttpHopTransport(testLimits(4), 1, 1)) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> transport.execute(target("/headers"), Duration.ofSeconds(2)));

            assertEquals(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, failure.kind());
        }
    }

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void cancelsWhileWaitingForHeadersAndReleasesTheWorker(StopReason reason) throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        server.handle("/slow", exchange -> {
            try (exchange) {
                requestStarted.countDown();
                await(releaseHeaders);
                exchange.sendResponseHeaders(204, -1);
            }
        });
        ApprovedTarget slow = target("/slow");
        try (var transport = new ApacheHttpHopTransport(testLimits(), 1, 1)) {
            AtomicReference<OutboundHttpFailure> failure = new AtomicReference<>();
            AtomicBoolean callerInterrupted = new AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    transport.execute(slow,
                            Duration.ofSeconds(reason == StopReason.TIMEOUT ? 1 : 10));
                } catch (OutboundHttpFailure exception) {
                    failure.set(exception);
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                }
            }, "test-header-caller");
            caller.start();
            try {
                assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
                switch (reason) {
                    case TIMEOUT -> { }
                    case CALLER_INTERRUPTED -> caller.interrupt();
                    case SHUTDOWN -> transport.close();
                }
                caller.join(3_000);
                assertFalse(caller.isAlive());
                if (reason == StopReason.TIMEOUT) {
                    // 전체 기한과 소켓 응답 기한 중 먼저 만료된 경로가 결과를 결정한다.
                    assertTrue(failure.get().kind() == OutboundHttpFailure.Kind.CONNECT_TIMEOUT
                            || failure.get().kind() == OutboundHttpFailure.Kind.READ_TIMEOUT);
                } else {
                    assertEquals(OutboundHttpFailure.Kind.INTERNAL_FAILURE, failure.get().kind());
                }
                assertEquals(reason == StopReason.CALLER_INTERRUPTED, callerInterrupted.get());
                releaseHeaders.countDown();
                if (reason != StopReason.SHUTDOWN) {
                    assertEquals(204, transport.execute(target("/quick"),
                            Duration.ofSeconds(1)).statusCode());
                }
            } finally {
                releaseHeaders.countDown();
                caller.interrupt();
                caller.join(1_000);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 999_999})
    void keepsSubMillisecondResponseTimeoutsEnabled(long timeoutNanos) throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        server.handle("/slow", exchange -> {
            try (exchange) {
                requestStarted.countDown();
                await(releaseHeaders);
                exchange.sendResponseHeaders(204, -1);
            }
        });
        CheckerLimits limits = new CheckerLimits(
                Duration.ofSeconds(1), Duration.ofNanos(timeoutNanos), Duration.ofSeconds(10),
                100, 8_192);
        ApprovedTarget target = target("/slow");
        try (var transport = new ApacheHttpHopTransport(limits, 1, 1)) {
            AtomicReference<OutboundHttpFailure> failure = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    transport.execute(target, Duration.ofSeconds(10));
                } catch (OutboundHttpFailure exception) {
                    failure.set(exception);
                }
            }, "test-submillisecond-timeout");
            caller.start();
            try {
                assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
                caller.join(2_000);
                assertFalse(caller.isAlive(), "응답 제한이 꺼져 전체 요청 기한까지 대기했습니다");
                assertEquals(OutboundHttpFailure.Kind.READ_TIMEOUT, failure.get().kind());
            } finally {
                releaseHeaders.countDown();
                caller.interrupt();
                caller.join(1_000);
            }
        }
    }

    private enum StopReason { TIMEOUT, CALLER_INTERRUPTED, SHUTDOWN }

    private ApprovedTarget target(String path) {
        return target("check.test", path);
    }

    private ApprovedTarget target(String hostname, String path) {
        URI uri = server.uri(hostname, path);
        ValidatedUri validated = new ValidatedUri(uri, "http", hostname, uri.toString());
        return new ApprovedTarget(validated, List.of(InetAddress.getLoopbackAddress()));
    }

    private static CheckerLimits testLimits() {
        return testLimits(100);
    }

    private static CheckerLimits testLimits(int maxHeaderCount) {
        return new CheckerLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                maxHeaderCount,
                8_192);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
