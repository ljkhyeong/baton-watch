package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.http.ApacheHttpClientLimits;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpRequestExecutor;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.io.CloseMode;

/** 이미 검증되고 DNS에 고정된 단일 홉용 Apache HttpClient 5 전송 구현. */
public final class ApacheHttpHopTransport implements HttpHopTransport, AutoCloseable {

    private final ApacheHttpClientLimits clientLimits;
    private final ApacheHttpRequestExecutor requestExecutor;

    public ApacheHttpHopTransport(CheckerLimits limits, int threadCount, int queueCapacity) {
        this.clientLimits = new ApacheHttpClientLimits(
                limits.connectTimeout(),
                limits.responseTimeout(),
                limits.maxHeaderCount(),
                limits.maxHeaderLineLength());
        this.requestExecutor = new ApacheHttpRequestExecutor(
                threadCount, queueCapacity, "watch-http-");
    }

    @Override
    public HttpHopResponse execute(ApprovedTarget target, Duration remainingTime)
            throws OutboundHttpFailure {
        return requestExecutor.executePinned(
                new HttpGet(target.target().uri()),
                target.target().hostname(),
                target.addresses(),
                clientLimits,
                remainingTime,
                CloseMode.IMMEDIATE,
                response -> {
                    List<String> locations = Arrays.stream(response.getHeaders(HttpHeaders.LOCATION))
                            .map(Header::getValue)
                            .toList();
                    // 도달 여부는 응답 헤더로 판단하고 본문을 읽거나 비우지 않는다.
                    return new HttpHopResponse(response.getCode(), locations);
                });
    }

    @Override
    public void close() {
        requestExecutor.close();
    }
}
