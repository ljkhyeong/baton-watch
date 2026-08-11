package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the JVM resolver in a bounded executor. Cancelling a future cannot force
 * the platform resolver itself to stop; the bounded pool prevents unbounded
 * thread creation while an infrastructure egress policy remains defense in depth.
 */
public final class BoundedDnsLookup implements DnsLookup, AutoCloseable {

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String hostname) throws UnknownHostException;
    }

    private final ExecutorService executor;
    private final HostResolver resolver;

    public BoundedDnsLookup(int threadCount, int queueCapacity) {
        this(threadCount, queueCapacity, InetAddress::getAllByName);
    }

    BoundedDnsLookup(int threadCount, int queueCapacity, HostResolver resolver) {
        this(createExecutor(threadCount, queueCapacity), resolver);
    }

    BoundedDnsLookup(ExecutorService executor, HostResolver resolver) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public List<InetAddress> resolve(String hostname, Duration timeout) throws DnsLookupException {
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(timeout, "timeout");
        if (!timeout.isPositive()) {
            throw new DnsLookupException(DnsLookupException.Reason.TIMED_OUT);
        }

        Future<InetAddress[]> future;
        try {
            future = executor.submit(() -> resolver.resolve(hostname));
        } catch (RejectedExecutionException exception) {
            throw new DnsLookupException(DnsLookupException.Reason.CAPACITY_EXHAUSTED);
        }

        try {
            InetAddress[] resolved = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (resolved == null || resolved.length == 0) {
                throw new DnsLookupException(DnsLookupException.Reason.NOT_FOUND);
            }
            return List.of(resolved);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new DnsLookupException(DnsLookupException.Reason.TIMED_OUT);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DnsLookupException(DnsLookupException.Reason.INTERRUPTED);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof UnknownHostException) {
                throw new DnsLookupException(DnsLookupException.Reason.NOT_FOUND);
            }
            throw new DnsLookupException(DnsLookupException.Reason.FAILED);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
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
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
