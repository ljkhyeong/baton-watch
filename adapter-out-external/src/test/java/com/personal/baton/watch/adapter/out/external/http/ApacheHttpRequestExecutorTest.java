package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

class ApacheHttpRequestExecutorTest {

    @Test
    void reportsConnectTimeoutAndCancelsWorkBeforeAResponseStarts() throws Exception {
        assertTimedOut(false, ApacheHttpFailure.Kind.CONNECT_TIMEOUT);
    }

    @Test
    void reportsReadTimeoutAndCancelsWorkAfterAResponseStarts() throws Exception {
        assertTimedOut(true, ApacheHttpFailure.Kind.READ_TIMEOUT);
    }

    @Test
    void restoresCallerInterruptAndCancelsTheRequest() throws Exception {
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicReference<ApacheHttpFailure.Kind> failureKind = new AtomicReference<>();
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
                } catch (ApacheHttpFailure failure) {
                    failureKind.set(failure.kind());
                    callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                }
            }, "test-http-caller");
            caller.start();

            assertTrue(operationStarted.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(1_000);

            assertFalse(caller.isAlive());
            assertEquals(ApacheHttpFailure.Kind.INTERNAL_FAILURE, failureKind.get());
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
            ApacheHttpFailure failure = assertThrows(
                    ApacheHttpFailure.class,
                    () -> executor.execute(Duration.ofSeconds(1), progress -> null));

            assertEquals(ApacheHttpFailure.Kind.INTERNAL_FAILURE, failure.kind());
        }

        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor();
        ApacheHttpRequestExecutor executor = new ApacheHttpRequestExecutor(ownedExecutor);
        executor.close();

        assertTrue(ownedExecutor.isShutdown());
    }

    @Test
    void preservesTheBoundedBlockingFailureTaxonomy() {
        assertBlockingFailure(
                ApacheHttpFailure.Kind.TLS_FAILURE,
                progress -> {
                    throw new SSLException("sensitive TLS detail");
                });
        assertBlockingFailure(
                ApacheHttpFailure.Kind.READ_TIMEOUT,
                progress -> {
                    throw new SocketTimeoutException("sensitive timeout detail");
                });
        assertBlockingFailure(
                ApacheHttpFailure.Kind.INTERNAL_FAILURE,
                progress -> {
                    throw new UnknownHostException("pinned resolver mismatch");
                });
        assertBlockingFailure(
                ApacheHttpFailure.Kind.NETWORK_FAILURE,
                progress -> {
                    throw new IOException("sensitive network detail");
                });
        assertBlockingFailure(
                ApacheHttpFailure.Kind.INTERNAL_FAILURE,
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
    }

    private static void assertTimedOut(
            boolean responseStarted, ApacheHttpFailure.Kind expectedKind) throws Exception {
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicReference<ApacheHttpFailure> observedFailure = new AtomicReference<>();

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
                } catch (ApacheHttpFailure failure) {
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
            ApacheHttpFailure.Kind expectedKind,
            ApacheHttpRequestExecutor.Operation<Void> operation) {
        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            ApacheHttpFailure failure = assertThrows(
                    ApacheHttpFailure.class,
                    () -> executor.execute(Duration.ofSeconds(1), operation));

            assertEquals(expectedKind, failure.kind());
            assertEquals("Apache HTTP request failed", failure.getMessage());
        }
    }
}
