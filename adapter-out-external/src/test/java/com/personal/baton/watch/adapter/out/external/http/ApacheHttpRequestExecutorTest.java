package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.io.IOFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApacheHttpRequestExecutorTest {

    @Test
    void alreadyInterruptedCallerCancelsTheRequestWithoutCallingTheOperation() {
        AtomicBoolean operationCalled = new AtomicBoolean();
        HttpGet request = new HttpGet("https://check.test/cancelled");
        try (var executor = new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            try {
                Thread.currentThread().interrupt();
                OutboundHttpFailure failure = assertThrows(OutboundHttpFailure.class,
                        () -> executor.execute(request, Duration.ofSeconds(1), started -> {
                            operationCalled.set(true);
                            return "sent";
                        }));
                assertEquals(OutboundHttpFailure.Kind.INTERNAL_FAILURE, failure.kind());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
        assertFalse(operationCalled.get());
        assertTrue(request.isCancelled());
    }

    @ParameterizedTest
    @CsvSource({"false, CONNECT_TIMEOUT", "true, READ_TIMEOUT"})
    void reportsTimeoutByResponseStartAndCancelsWork(
            boolean responseStarted, OutboundHttpFailure.Kind expectedKind) throws Exception {
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicReference<OutboundHttpFailure> observedFailure = new AtomicReference<>();
        HttpGet request = new HttpGet("https://check.test/");

        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            Thread caller = new Thread(() -> {
                try {
                    executor.execute(request, Duration.ofMillis(500), onResponseStarted -> {
                        if (responseStarted) {
                            onResponseStarted.run();
                        }
                        operationStarted.countDown();
                        try {
                            block.await();
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            throw new InterruptedIOException("cancelled");
                        }
                        return null;
                    });
                } catch (OutboundHttpFailure failure) {
                    observedFailure.set(failure);
                }
            }, "test-timeout-caller");
            caller.start();

            assertTrue(operationStarted.await(1, TimeUnit.SECONDS));
            caller.join(1_500);

            assertFalse(caller.isAlive());
            assertEquals(expectedKind, observedFailure.get().kind());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertTrue(request.isCancelled());
        }
    }

    @Test
    void preservesTheBoundedBlockingFailureTaxonomy() {
        assertBlockingFailure(
                OutboundHttpFailure.Kind.TLS_FAILURE,
                onResponseStarted -> {
                    throw new SSLException("sensitive TLS detail");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.READ_TIMEOUT,
                onResponseStarted -> {
                    throw new SocketTimeoutException("sensitive timeout detail");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.INTERNAL_FAILURE,
                onResponseStarted -> {
                    throw new UnknownHostException("pinned resolver mismatch");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.NETWORK_FAILURE,
                onResponseStarted -> {
                    throw new IOException("sensitive network detail");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE,
                onResponseStarted -> {
                    throw new MessageConstraintException("sensitive parser detail");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.INTERNAL_FAILURE,
                onResponseStarted -> {
                    throw new IllegalStateException("sensitive adapter detail");
                });
    }

    @Test
    void rejectsExecutorBoundsOutsideTheImplementationLimits() {
        assertThrows(IllegalArgumentException.class, () -> new ApacheHttpRequestExecutor(0, 1, "test-http-"));
        assertThrows(IllegalArgumentException.class, () -> new ApacheHttpRequestExecutor(1, 0, "test-http-"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHttpRequestExecutor(
                        OutboundResourceBounds.MAX_REQUEST_THREADS + 1,
                        1,
                        "test-http-"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHttpRequestExecutor(
                        1,
                        OutboundResourceBounds.MAX_REQUEST_QUEUE_CAPACITY + 1,
                        "test-http-"));
    }

    private static void assertBlockingFailure(
            OutboundHttpFailure.Kind expectedKind,
            IOFunction<Runnable, Void> operation) {
        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> executor.execute(new HttpGet("https://check.test/"), Duration.ofSeconds(1), operation));

            assertEquals(expectedKind, failure.kind());
            assertEquals("outbound HTTP request failed", failure.getMessage());
        }
    }
}
