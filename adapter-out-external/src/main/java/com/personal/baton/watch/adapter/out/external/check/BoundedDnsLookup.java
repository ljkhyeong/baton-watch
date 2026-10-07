package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.BoundedTaskExecutor;
import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.hc.core5.io.IOFunction;

/**
 * JVM DNS 조회를 제한된 스레드 풀에서 실행한다. Future를 취소해도 DNS 조회 자체는
 * 강제로 중지할 수 없다. 스레드 수를 제한하고 인프라의 외부 통신 차단 정책도 유지한다.
 */
public final class BoundedDnsLookup implements DnsLookup {

    private final BoundedTaskExecutor executor;
    private final IOFunction<String, InetAddress[]> resolver;

    public BoundedDnsLookup(int threadCount, int queueCapacity) {
        this(threadCount, queueCapacity, InetAddress::getAllByName);
    }

    BoundedDnsLookup(
            int threadCount, int queueCapacity, IOFunction<String, InetAddress[]> resolver) {
        OutboundResourceBounds.requireDnsExecutorBounds(threadCount, queueCapacity);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.executor = new BoundedTaskExecutor(threadCount, queueCapacity, "watch-dns-");
    }

    @Override
    public List<InetAddress> resolve(String hostname, Duration timeout) throws OutboundHttpFailure {
        // JVM은 null 호스트를 루프백으로 해석하므로 조회 전에 거부한다.
        Objects.requireNonNull(hostname, "hostname");
        try {
            return List.of(executor.call(() -> resolver.apply(hostname), timeout, () -> {}));
        } catch (TimeoutException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.DNS_FAILURE);
        } catch (ExecutionException exception) {
            throw new OutboundHttpFailure(exception.getCause() instanceof UnknownHostException
                    ? OutboundHttpFailure.Kind.DNS_FAILURE
                    : OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        } catch (CancellationException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
