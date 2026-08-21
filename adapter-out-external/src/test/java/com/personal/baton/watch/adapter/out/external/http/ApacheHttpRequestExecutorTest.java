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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.io.IOFunction;
import org.junit.jupiter.api.Test;

class ApacheHttpRequestExecutorTest {

    @Test
    void reportsConnectTimeoutAndCancelsWorkBeforeAResponseStarts() throws Exception {
        assertTimedOut(false, OutboundHttpFailure.Kind.CONNECT_TIMEOUT);
    }

    @Test
    void reportsReadTimeoutAndCancelsWorkAfterAResponseStarts() throws Exception {
        assertTimedOut(true, OutboundHttpFailure.Kind.READ_TIMEOUT);
    }

    @Test
    void restoresCallerInterruptAndCancelsTheRequest() throws Exception {
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicReference<OutboundHttpFailure.Kind> failureKind = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();

        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            Thread caller = new Thread(() -> {
                try {
                    executor.execute(Duration.ofSeconds(5), progress -> {
                        operationStarted.countDown();
                        try {
                            block.await();
                        } catch (InterruptedException exception) {
                            workerInterrupted.countDown();
                            throw new InterruptedIOException("cancelled");
                        }
                        return null;
                    });
                } catch (OutboundHttpFailure failure) {
                    failureKind.set(failure.kind());
                    callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                }
            }, "test-http-caller");
            caller.start();

            assertTrue(operationStarted.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(1_000);

            assertFalse(caller.isAlive());
            assertEquals(OutboundHttpFailure.Kind.INTERNAL_FAILURE, failureKind.get());
            assertTrue(callerInterruptRestored.get());
            assertTrue(workerInterrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void mapsRejectedWorkToInternalFailureAndOwnsExecutorShutdown() {
        ExecutorService rejectedExecutor = Executors.newSingleThreadExecutor();
        rejectedExecutor.shutdownNow();
        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(rejectedExecutor)) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> executor.execute(Duration.ofSeconds(1), progress -> null));

            assertEquals(OutboundHttpFailure.Kind.INTERNAL_FAILURE, failure.kind());
        }

        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor();
        ApacheHttpRequestExecutor executor = new ApacheHttpRequestExecutor(ownedExecutor);
        executor.close();

        assertTrue(ownedExecutor.isShutdown());
    }

    @Test
    void createsNamedDaemonThreads() throws Exception {
        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            Thread worker = executor.execute(
                    Duration.ofSeconds(1), progress -> Thread.currentThread());

            assertEquals("test-http-1", worker.getName());
            assertTrue(worker.isDaemon());
        }
    }

    @Test
    void preservesTheBoundedBlockingFailureTaxonomy() {
        assertBlockingFailure(
                OutboundHttpFailure.Kind.TLS_FAILURE,
                progress -> {
                    throw new SSLException("sensitive TLS detail");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.READ_TIMEOUT,
                progress -> {
                    throw new SocketTimeoutException("sensitive timeout detail");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.INTERNAL_FAILURE,
                progress -> {
                    throw new UnknownHostException("pinned resolver mismatch");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.NETWORK_FAILURE,
                progress -> {
                    throw new IOException("sensitive network detail");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE,
                progress -> {
                    throw new MessageConstraintException("sensitive parser detail");
                });
        assertBlockingFailure(
                OutboundHttpFailure.Kind.INTERNAL_FAILURE,
                progress -> {
                    throw new IllegalStateException("sensitive adapter detail");
                });
    }

    @Test
    void rejectsDisabledExecutorBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHttpRequestExecutor(0, 1, "test-http-"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApacheHttpRequestExecutor(1, 0, "test-http-"));
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

    private static void assertTimedOut(
            boolean responseStarted, OutboundHttpFailure.Kind expectedKind) throws Exception {
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicReference<OutboundHttpFailure> observedFailure = new AtomicReference<>();

        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            Thread caller = new Thread(() -> {
                try {
                    executor.execute(Duration.ofSeconds(1), progress -> {
                        if (responseStarted) {
                            progress.responseStarted();
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
            caller.join(2_000);

            assertFalse(caller.isAlive());
            assertEquals(expectedKind, observedFailure.get().kind());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
    }

    private static void assertBlockingFailure(
            OutboundHttpFailure.Kind expectedKind,
            IOFunction<ApacheHttpRequestExecutor.Progress, Void> operation) {
        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> executor.execute(Duration.ofSeconds(1), operation));

            assertEquals(expectedKind, failure.kind());
            assertEquals("outbound HTTP request failed", failure.getMessage());
        }
    }
}
