package com.personal.baton.watch.adapter.out.external.http;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentTooLongException;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.io.IOFunction;
import org.apache.hc.core5.util.Args;

/** 제한된 HTTP 실행기를 소유하고 각 요청에 하나의 강제 기한을 적용한다. */
public final class ApacheHttpRequestExecutor implements AutoCloseable {

    private final ExecutorService executor;
    private final Set<FutureTask<?>> requests = ConcurrentHashMap.newKeySet();

    public ApacheHttpRequestExecutor(
            int threadCount, int queueCapacity, String threadNamePrefix) {
        this(createExecutor(threadCount, queueCapacity, threadNamePrefix));
    }

    ApacheHttpRequestExecutor(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public <T> T execute(HttpUriRequestBase request, Duration timeout, IOFunction<Runnable, T> operation)
            throws OutboundHttpFailure {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(operation, "operation");
        if (!timeout.isPositive()) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.CONNECT_TIMEOUT);
        }

        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        }

        AtomicBoolean responseStarted = new AtomicBoolean();
        FutureTask<T> future = new FutureTask<>(
                () -> executeBlocking(operation, () -> responseStarted.set(true))) {
            @Override
            protected void done() {
                // 스레드 인터럽트만으로는 플랫폼 스레드의 소켓 읽기를 중단할 수 없다.
                if (isCancelled()) {
                    request.cancel();
                }
                requests.remove(this);
            }
        };
        requests.add(future);
        try {
            executor.execute(future);
        } catch (RejectedExecutionException exception) {
            future.cancel(true);
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        }

        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            OutboundHttpFailure.Kind kind = responseStarted.get()
                    ? OutboundHttpFailure.Kind.READ_TIMEOUT
                    : OutboundHttpFailure.Kind.CONNECT_TIMEOUT;
            throw new OutboundHttpFailure(kind);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        } catch (CancellationException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof OutboundHttpFailure httpFailure) {
                throw httpFailure;
            }
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        requests.forEach(request -> request.cancel(true));
        executor.shutdownNow();
    }

    private static <T> T executeBlocking(
            IOFunction<Runnable, T> operation, Runnable onResponseStarted)
            throws OutboundHttpFailure {
        try {
            return operation.apply(onResponseStarted);
        } catch (ContentTooLongException | MessageConstraintException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE);
        } catch (ConnectTimeoutException | ConnectionRequestTimeoutException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.CONNECT_TIMEOUT);
        } catch (SSLException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.TLS_FAILURE);
        } catch (SocketTimeoutException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.READ_TIMEOUT);
        } catch (UnknownHostException exception) {
            // 고정 리졸버 불일치는 새로운 DNS 조회 사유가 아니라 어댑터 불변식 위반이다.
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        } catch (InterruptedIOException exception) {
            Thread.currentThread().interrupt();
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        } catch (IOException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.NETWORK_FAILURE);
        }
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

}
