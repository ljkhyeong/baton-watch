package com.personal.baton.watch.adapter.out.external.http;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.MessageConstraintException;

/** Owns a bounded HTTP executor and applies one hard deadline to each request. */
public final class ApacheHttpRequestExecutor implements AutoCloseable {

    @FunctionalInterface
    public interface Operation<T> {

        T execute(Progress progress) throws IOException;
    }

    public interface Progress {

        void responseStarted();

        void responseBytes(long responseBytes);
    }

    private enum Phase {
        CONNECTING,
        READING
    }

    private final ExecutorService executor;

    public ApacheHttpRequestExecutor(
            int threadCount, int queueCapacity, String threadNamePrefix) {
        this(createExecutor(threadCount, queueCapacity, threadNamePrefix));
    }

    ApacheHttpRequestExecutor(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public <T> T execute(Duration timeout, Operation<T> operation) throws ApacheHttpFailure {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(operation, "operation");
        if (!timeout.isPositive()) {
            throw failure(ApacheHttpFailure.Kind.CONNECT_TIMEOUT, 0);
        }

        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw failure(ApacheHttpFailure.Kind.INTERNAL_FAILURE, 0);
        }

        RequestProgress progress = new RequestProgress();
        Future<T> future;
        try {
            future = executor.submit(() -> executeBlocking(operation, progress));
        } catch (RejectedExecutionException exception) {
            throw failure(ApacheHttpFailure.Kind.INTERNAL_FAILURE, 0);
        }

        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            ApacheHttpFailure.Kind kind = progress.phase() == Phase.CONNECTING
                    ? ApacheHttpFailure.Kind.CONNECT_TIMEOUT
                    : ApacheHttpFailure.Kind.READ_TIMEOUT;
            throw failure(kind, progress.responseBytes());
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(ApacheHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ApacheHttpFailure httpFailure) {
                throw httpFailure;
            }
            throw failure(ApacheHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static <T> T executeBlocking(Operation<T> operation, RequestProgress progress)
            throws ApacheHttpFailure {
        try {
            return operation.execute(progress);
        } catch (ResponseBodyDiscarder.ResponseTooLargeException exception) {
            throw failure(
                    ApacheHttpFailure.Kind.RESPONSE_TOO_LARGE,
                    Math.max(progress.responseBytes(), exception.consumedWithinLimit()));
        } catch (MessageConstraintException exception) {
            throw failure(ApacheHttpFailure.Kind.RESPONSE_TOO_LARGE, progress.responseBytes());
        } catch (ConnectTimeoutException | ConnectionRequestTimeoutException exception) {
            throw failure(ApacheHttpFailure.Kind.CONNECT_TIMEOUT, progress.responseBytes());
        } catch (SSLException exception) {
            throw failure(ApacheHttpFailure.Kind.TLS_FAILURE, progress.responseBytes());
        } catch (SocketTimeoutException exception) {
            throw failure(ApacheHttpFailure.Kind.READ_TIMEOUT, progress.responseBytes());
        } catch (UnknownHostException exception) {
            // A pinned resolver mismatch is an adapter invariant failure, not a fresh DNS lookup.
            throw failure(ApacheHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        } catch (InterruptedIOException exception) {
            Thread.currentThread().interrupt();
            throw failure(ApacheHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        } catch (IOException exception) {
            throw failure(ApacheHttpFailure.Kind.NETWORK_FAILURE, progress.responseBytes());
        } catch (RuntimeException exception) {
            throw failure(ApacheHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        }
    }

    private static ApacheHttpFailure failure(ApacheHttpFailure.Kind kind, long responseBytes) {
        return new ApacheHttpFailure(kind, responseBytes);
    }

    private static ExecutorService createExecutor(
            int threadCount, int queueCapacity, String threadNamePrefix) {
        OutboundResourceBounds.requireRequestExecutorBounds(threadCount, queueCapacity);
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("HTTP thread name prefix must not be blank");
        }
        return new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform()
                        .daemon()
                        .name(threadNamePrefix, 1)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class RequestProgress implements Progress {

        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.CONNECTING);
        private final AtomicLong responseBytes = new AtomicLong();

        @Override
        public void responseStarted() {
            phase.set(Phase.READING);
        }

        @Override
        public void responseBytes(long currentResponseBytes) {
            if (currentResponseBytes < 0) {
                throw new IllegalArgumentException("responseBytes must be non-negative");
            }
            responseBytes.accumulateAndGet(currentResponseBytes, Math::max);
        }

        private Phase phase() {
            return phase.get();
        }

        private long responseBytes() {
            return responseBytes.get();
        }
    }
}
