package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.http.ApacheHttpClientLimits;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpRequestExecutor;
import com.personal.baton.watch.adapter.out.external.http.ApacheResponseLifecycle;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.adapter.out.external.http.PinnedApacheClientFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.io.CloseMode;

/** 이미 검증되고 DNS에 고정된 단일 홉용 Apache HttpClient 5 전송 구현. */
public final class ApacheHttpHopTransport implements HttpHopTransport, AutoCloseable {

    private final CheckerLimits limits;
    private final ApacheHttpRequestExecutor requestExecutor;
    private final PinnedApacheClientFactory clientFactory;

    public ApacheHttpHopTransport(CheckerLimits limits, int threadCount, int queueCapacity) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clientFactory = new PinnedApacheClientFactory();
        this.requestExecutor = new ApacheHttpRequestExecutor(
                threadCount, queueCapacity, "watch-http-");
    }

    @Override
    public HttpHopResponse execute(ApprovedTarget target, Duration remainingTime)
            throws OutboundHttpFailure {
        HttpGet request = new HttpGet(target.target().uri());
        return requestExecutor.execute(
                request,
                remainingTime,
                onResponseStarted -> executeBlocking(
                        target, request, remainingTime, onResponseStarted));
    }

    @Override
    public void close() {
        requestExecutor.close();
    }

    private HttpHopResponse executeBlocking(
            ApprovedTarget target,
            HttpGet request,
            Duration remainingTime,
            Runnable onResponseStarted)
            throws IOException {
        ApacheHttpClientLimits clientLimits = ApacheHttpClientLimits.cappedBy(
                limits.connectTimeout(),
                limits.responseTimeout(),
                remainingTime,
                limits.maxHeaderCount(),
                limits.maxHeaderLineLength());

        try (CloseableHttpClient client = clientFactory.open(
                target.target().hostname(), target.addresses(), clientLimits)) {
            return ApacheResponseLifecycle.execute(
                    client, HttpHost.create(target.target().uri()), request, CloseMode.IMMEDIATE, response -> {
                        onResponseStarted.run();
                        List<String> locations = Arrays.stream(response.getHeaders(HttpHeaders.LOCATION))
                                .map(Header::getValue)
                                .map(value -> Objects.requireNonNullElse(value, ""))
                                .toList();
                        // 도달 여부는 응답 헤더로 판단하고 본문을 읽거나 비우지 않는다.
                        return new HttpHopResponse(response.getCode(), locations);
                    });
        }
    }

}
