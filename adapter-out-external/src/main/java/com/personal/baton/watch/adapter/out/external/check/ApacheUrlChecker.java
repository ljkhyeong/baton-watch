package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.port.out.UrlChecker;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.util.Objects;

/** Production checker facade. It owns bounded DNS and HTTP executors. */
public final class ApacheUrlChecker implements UrlChecker, AutoCloseable {

    private static final int DNS_THREADS = 4;
    private static final int DNS_QUEUE_CAPACITY = 16;
    private static final int HTTP_THREADS = 1;
    private static final int HTTP_QUEUE_CAPACITY = 4;

    private final SafeUrlCheckEngine engine;
    private final AutoCloseable dnsLookup;
    private final AutoCloseable transport;

    public ApacheUrlChecker(CheckerLimits limits) {
        this(limits, DNS_THREADS, DNS_QUEUE_CAPACITY, HTTP_THREADS, HTTP_QUEUE_CAPACITY);
    }

    public ApacheUrlChecker(
            CheckerLimits limits,
            int dnsThreadCount,
            int dnsQueueCapacity,
            int httpThreadCount,
            int httpQueueCapacity) {
        Objects.requireNonNull(limits, "limits");
        BoundedDnsLookup boundedDnsLookup = new BoundedDnsLookup(dnsThreadCount, dnsQueueCapacity);
        ApacheHttpHopTransport apacheTransport =
                new ApacheHttpHopTransport(limits, httpThreadCount, httpQueueCapacity);
        this.engine = new SafeUrlCheckEngine(
                limits,
                new TargetUriPolicy(),
                boundedDnsLookup,
                new GlobalAddressPolicy(),
                apacheTransport,
                System::nanoTime);
        this.dnsLookup = boundedDnsLookup;
        this.transport = apacheTransport;
    }

    ApacheUrlChecker(
            SafeUrlCheckEngine engine, AutoCloseable dnsLookup, AutoCloseable transport) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.dnsLookup = Objects.requireNonNull(dnsLookup, "dnsLookup");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CheckObservation check(TargetUrl targetUrl) {
        return engine.check(targetUrl);
    }

    @Override
    public void close() {
        closeQuietly(transport);
        closeQuietly(dnsLookup);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Shutdown is best effort and intentionally does not expose exception details.
        }
    }
}
