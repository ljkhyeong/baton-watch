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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentTooLongException;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.io.IOFunction;
import org.apache.hc.core5.util.Args;

/** 제한된 HTTP 실행기를 소유하고 각 요청에 하나의 강제 기한을 적용한다. */
public final class ApacheHttpRequestExecutor implements AutoCloseable {

    public interface Progress {

        void responseStarted();

        void responseBytes(long responseBytes);
    }

    private final ExecutorService executor;

    public ApacheHttpRequestExecutor(
            int threadCount, int queueCapacity, String threadNamePrefix) {
        this(createExecutor(threadCount, queueCapacity, threadNamePrefix));
    }

    ApacheHttpRequestExecutor(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public <T> T execute(Duration timeout, IOFunction<Progress, T> operation)
            throws OutboundHttpFailure {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(operation, "operation");
        if (!timeout.isPositive()) {
            throw failure(OutboundHttpFailure.Kind.CONNECT_TIMEOUT, 0);
        }

        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw failure(OutboundHttpFailure.Kind.INTERNAL_FAILURE, 0);
        }

        RequestProgress progress = new RequestProgress();
        Future<T> future;
        try {
            future = executor.submit(() -> executeBlocking(operation, progress));
        } catch (RejectedExecutionException exception) {
            throw failure(OutboundHttpFailure.Kind.INTERNAL_FAILURE, 0);
        }

        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            OutboundHttpFailure.Kind kind = progress.hasResponseStarted()
                    ? OutboundHttpFailure.Kind.READ_TIMEOUT
                    : OutboundHttpFailure.Kind.CONNECT_TIMEOUT;
            throw failure(kind, progress.responseBytes());
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(OutboundHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof OutboundHttpFailure httpFailure) {
                throw httpFailure;
            }
            throw failure(OutboundHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static <T> T executeBlocking(
            IOFunction<Progress, T> operation, RequestProgress progress)
            throws OutboundHttpFailure {
        try {
            return operation.apply(progress);
        } catch (ContentTooLongException | MessageConstraintException exception) {
            throw failure(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, progress.responseBytes());
        } catch (ConnectTimeoutException | ConnectionRequestTimeoutException exception) {
            throw failure(OutboundHttpFailure.Kind.CONNECT_TIMEOUT, progress.responseBytes());
        } catch (SSLException exception) {
            throw failure(OutboundHttpFailure.Kind.TLS_FAILURE, progress.responseBytes());
        } catch (SocketTimeoutException exception) {
            throw failure(OutboundHttpFailure.Kind.READ_TIMEOUT, progress.responseBytes());
        } catch (UnknownHostException exception) {
            // 고정 리졸버 불일치는 새로운 DNS 조회 사유가 아니라 어댑터 불변식 위반이다.
            throw failure(OutboundHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        } catch (InterruptedIOException exception) {
            Thread.currentThread().interrupt();
            throw failure(OutboundHttpFailure.Kind.INTERNAL_FAILURE, progress.responseBytes());
        } catch (IOException exception) {
            throw failure(OutboundHttpFailure.Kind.NETWORK_FAILURE, progress.responseBytes());
        }
    }

    private static OutboundHttpFailure failure(
            OutboundHttpFailure.Kind kind, long responseBytes) {
        return new OutboundHttpFailure(kind, responseBytes);
    }

    private static ExecutorService createExecutor(
            int threadCount, int queueCapacity, String threadNamePrefix) {
        OutboundResourceBounds.requireRequestExecutorBounds(threadCount, queueCapacity);
        Args.notBlank(threadNamePrefix, "HTTP thread name prefix");
        return new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform()
                        .daemon()
                        .name(threadNamePrefix, 1)
                        .factory());
    }

    private static final class RequestProgress implements Progress {

        private final AtomicBoolean responseStarted = new AtomicBoolean();
        private final AtomicLong responseBytes = new AtomicLong();

        @Override
        public void responseStarted() {
            responseStarted.set(true);
        }

        @Override
        public void responseBytes(long currentResponseBytes) {
            responseBytes.accumulateAndGet(currentResponseBytes, Math::max);
        }

        private boolean hasResponseStarted() {
            return responseStarted.get();
        }

        private long responseBytes() {
            return responseBytes.get();
        }
    }
}
