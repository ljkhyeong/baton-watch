package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
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
        HttpGet request = new HttpGet("https://check.test/");

        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-http-")) {
            Thread caller = new Thread(() -> {
                try {
                    executor.execute(request, Duration.ofSeconds(5), onResponseStarted -> {
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
            assertTrue(request.isCancelled());
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
                    () -> executor.execute(new HttpGet("https://check.test/"), Duration.ofSeconds(1), onResponseStarted -> null));

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
                    new HttpGet("https://check.test/"), Duration.ofSeconds(1), onResponseStarted -> Thread.currentThread());

            assertTrue(worker.getName().startsWith("test-http-"));
            assertTrue(worker.isDaemon());
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
    void rejectsExecutorBoundsAboveTheImplementationCeilings() {
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

    @Test
    void shutdownCancelsQueuedRequestsWithoutWaitingForTheirDeadline() throws Exception {
        CountDownLatch workerOccupied = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        var queue = new ArrayBlockingQueue<Runnable>(1);
        var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue);
        worker.execute(() -> {
            workerOccupied.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        HttpGet request = new HttpGet("https://check.test/");
        AtomicBoolean operationCalled = new AtomicBoolean();
        try (var executor = new ApacheHttpRequestExecutor(worker);
                var caller = Executors.newSingleThreadExecutor()) {
            assertTrue(workerOccupied.await(1, TimeUnit.SECONDS));
            var result = caller.submit(() -> assertThrows(OutboundHttpFailure.class,
                    () -> executor.execute(request, Duration.ofSeconds(10), onResponseStarted -> {
                        operationCalled.set(true);
                        return null;
                    })));
            Runnable queued = queue.poll(1, TimeUnit.SECONDS);
            assertNotNull(queued);
            queue.add(queued);
            executor.close();

            assertEquals(OutboundHttpFailure.Kind.INTERNAL_FAILURE, result.get(1, TimeUnit.SECONDS).kind());
            assertTrue(request.isCancelled());
            assertFalse(operationCalled.get());
        } finally {
            releaseWorker.countDown();
            worker.shutdownNow();
        }
    }

    private static void assertTimedOut(
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
