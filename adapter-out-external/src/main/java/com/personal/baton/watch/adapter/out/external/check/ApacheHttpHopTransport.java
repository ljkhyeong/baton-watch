package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.http.ApacheHttpRequestExecutor;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.io.CloseMode;

/** 이미 검증되고 DNS에 고정된 단일 홉용 Apache HttpClient 5 전송 구현. */
public final class ApacheHttpHopTransport implements HttpHopTransport {

    private final CheckerLimits limits;
    private final ApacheHttpRequestExecutor requestExecutor;

    public ApacheHttpHopTransport(CheckerLimits limits, int threadCount, int queueCapacity) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.requestExecutor = new ApacheHttpRequestExecutor(
                threadCount, queueCapacity, "watch-http-");
    }

    @Override
    public HttpHopResponse execute(ApprovedTarget target, Duration remainingTime)
            throws OutboundHttpFailure {
        return requestExecutor.executePinned(
                // 요청 줄은 ASCII만 전달되므로 전송 직전에 인코딩한다. 순환 판정은 저장된 원문을 그대로 쓴다.
                new HttpGet(URI.create(target.target().uri().toASCIIString())),
                target.addresses(),
                limits,
                remainingTime,
                CloseMode.IMMEDIATE,
                response -> {
                    List<String> locations = Arrays.stream(response.getHeaders(HttpHeaders.LOCATION))
                            .map(Header::getValue)
                            .map(ApacheHttpHopTransport::restoreUtf8)
                            .toList();
                    // 도달 여부는 응답 헤더로 판단하고 본문을 읽거나 비우지 않는다.
                    return new HttpHopResponse(response.getCode(), locations);
                });
    }

    @Override
    public void close() {
        requestExecutor.close();
    }

    /** Apache는 헤더 바이트를 문자로 넓히므로 UTF-8 원문을 복원한다. UTF-8이 아니면 리다이렉트 정책에 맡긴다. */
    private static String restoreUtf8(String headerValue) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(headerValue.getBytes(StandardCharsets.ISO_8859_1)))
                    .toString();
        } catch (CharacterCodingException exception) {
            return headerValue;
        }
    }
}
