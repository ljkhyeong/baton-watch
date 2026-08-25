package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.port.out.UrlChecker;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.util.Objects;

/** 운영용 점검기 퍼사드. 제한된 DNS 및 HTTP 실행기를 소유한다. */
public final class ApacheUrlChecker implements UrlChecker, AutoCloseable {

    private final SafeUrlCheckEngine engine;
    private final AutoCloseable dnsLookup;
    private final AutoCloseable transport;

    public ApacheUrlChecker(
            CheckerLimits limits,
            int dnsThreadCount,
            int dnsQueueCapacity,
            int httpThreadCount,
            int httpQueueCapacity) {
        Objects.requireNonNull(limits, "limits");
        OutboundResourceBounds.requireDnsExecutorBounds(dnsThreadCount, dnsQueueCapacity);
        OutboundResourceBounds.requireRequestExecutorBounds(httpThreadCount, httpQueueCapacity);
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

    @Override
    public CheckObservation check(TargetUrl targetUrl) {
        return engine.check(targetUrl);
    }

    @Override
    public void close() {
        try (dnsLookup; transport) {
            // 등록 역순인 전송 계층, DNS 조회기 순서로 닫는다.
        } catch (Exception ignored) {
            // 종료는 최선을 다해 시도하며 예외 세부 정보를 의도적으로 노출하지 않는다.
        }
    }
}
