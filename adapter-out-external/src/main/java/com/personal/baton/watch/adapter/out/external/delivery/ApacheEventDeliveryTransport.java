package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.http.ApacheHttpRequestExecutor;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.adapter.out.external.http.ResponseBodyDiscarder;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;

/** 생성 시 고정한 콜백 주소로 DNS에 고정된 POST 하나를 리다이렉트나 클라이언트 상태 없이 실행한다. */
final class ApacheEventDeliveryTransport implements DeliveryTransport {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final URI endpoint;
    private final String authorization;
    private final EventDeliveryLimits limits;
    private final Clock clock;
    private final ApacheHttpRequestExecutor requestExecutor;
    private final ResponseBodyDiscarder bodyDiscarder = new ResponseBodyDiscarder();

    ApacheEventDeliveryTransport(
            URI endpoint,
            String bearerToken,
            EventDeliveryLimits limits,
            int threadCount,
            int queueCapacity,
            Clock clock) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.authorization = "Bearer " + Objects.requireNonNull(bearerToken, "bearerToken");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestExecutor = new ApacheHttpRequestExecutor(
                threadCount, queueCapacity, "watch-event-http-");
    }

    @Override
    public DeliveryResponse execute(
            List<InetAddress> addresses, byte[] payload, String idempotencyKey, Duration remainingTime)
            throws OutboundHttpFailure {
        HttpPost request = new HttpPost(endpoint);
        request.setHeader(HttpHeaders.AUTHORIZATION, authorization);
        request.setHeader(IDEMPOTENCY_KEY, idempotencyKey);
        request.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
        request.setEntity(new ByteArrayEntity(payload, ContentType.APPLICATION_JSON));

        return requestExecutor.executePinned(
                request,
                addresses,
                limits,
                remainingTime,
                CloseMode.GRACEFUL,
                response -> {
                    Instant retryNotBefore = retryNotBefore(response);
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        bodyDiscarder.discard(entity, limits.maxResponseBytes());
                    }
                    return new DeliveryResponse(response.getCode(), retryNotBefore);
                });
    }

    @Override
    public void close() {
        requestExecutor.close();
    }

    private Instant retryNotBefore(ClassicHttpResponse response) {
        if (response.getCode() != 429 && response.getCode() != 503) {
            return null;
        }
        Header[] headers = response.getHeaders(HttpHeaders.RETRY_AFTER);
        if (headers.length != 1) {
            return null;
        }
        String value = headers[0].getValue().trim();
        try {
            if (value.matches("[0-9]+")) {
                return clock.instant().plusSeconds(Long.parseLong(value));
            }
            return DateUtils.parseStandardDate(value);
        } catch (NumberFormatException | DateTimeException | ArithmeticException exception) {
            return null;
        }
    }

}
