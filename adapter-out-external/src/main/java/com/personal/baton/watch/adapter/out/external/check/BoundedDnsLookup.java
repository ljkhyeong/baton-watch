package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.core5.io.IOFunction;

/**
 * JVM DNS 조회를 제한된 스레드 풀에서 실행한다. Future를 취소해도 DNS 조회 자체는
 * 강제로 중지할 수 없다. 스레드 수를 제한하고 인프라의 외부 통신 차단 정책도 유지한다.
 */
public final class BoundedDnsLookup implements DnsLookup, AutoCloseable {

    private final ExecutorService executor;
    private final IOFunction<String, InetAddress[]> resolver;

    public BoundedDnsLookup(int threadCount, int queueCapacity) {
        this(threadCount, queueCapacity, InetAddress::getAllByName);
    }

    BoundedDnsLookup(
            int threadCount, int queueCapacity, IOFunction<String, InetAddress[]> resolver) {
        this(createExecutor(threadCount, queueCapacity), resolver);
    }

    BoundedDnsLookup(ExecutorService executor, IOFunction<String, InetAddress[]> resolver) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public List<InetAddress> resolve(String hostname, Duration timeout) throws DnsLookupException {
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(timeout, "timeout");
        if (!timeout.isPositive()) {
            throw new DnsLookupException(DnsLookupException.Reason.DNS_FAILURE);
        }

        Future<InetAddress[]> future;
        try {
            future = executor.submit(() -> resolver.apply(hostname));
        } catch (RejectedExecutionException exception) {
            throw new DnsLookupException(DnsLookupException.Reason.INTERNAL_FAILURE);
        }

        try {
            InetAddress[] resolved = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return List.of(resolved);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new DnsLookupException(DnsLookupException.Reason.DNS_FAILURE);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DnsLookupException(DnsLookupException.Reason.INTERNAL_FAILURE);
        } catch (CancellationException exception) {
            throw new DnsLookupException(DnsLookupException.Reason.INTERNAL_FAILURE);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof UnknownHostException) {
                throw new DnsLookupException(DnsLookupException.Reason.DNS_FAILURE);
            }
            throw new DnsLookupException(DnsLookupException.Reason.INTERNAL_FAILURE);
        }
    }

    @Override
    public void close() {
        for (Runnable queued : executor.shutdownNow()) {
            if (queued instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }

    private static ExecutorService createExecutor(int threadCount, int queueCapacity) {
        OutboundResourceBounds.requireDnsExecutorBounds(threadCount, queueCapacity);
        return new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform()
                        .daemon()
                        .name("watch-dns-", 1)
                        .factory());
    }
}
