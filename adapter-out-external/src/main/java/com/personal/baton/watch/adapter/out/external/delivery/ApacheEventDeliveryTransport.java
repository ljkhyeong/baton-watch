package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.http.ApacheHttpClientLimits;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpRequestExecutor;
import com.personal.baton.watch.adapter.out.external.http.ApacheResponseLifecycle;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.adapter.out.external.http.PinnedApacheClientFactory;
import com.personal.baton.watch.adapter.out.external.http.ResponseBodyDiscarder;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;

/** 이미 검증되고 DNS에 고정된 POST 하나를 리다이렉트나 클라이언트 상태 없이 실행한다. */
final class ApacheEventDeliveryTransport implements DeliveryTransport, AutoCloseable {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final EventDeliveryLimits limits;
    private final ApacheHttpRequestExecutor requestExecutor;
    private final PinnedApacheClientFactory clientFactory;
    private final ResponseBodyDiscarder bodyDiscarder = new ResponseBodyDiscarder();

    ApacheEventDeliveryTransport(EventDeliveryLimits limits, int threadCount, int queueCapacity) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clientFactory = new PinnedApacheClientFactory();
        this.requestExecutor = new ApacheHttpRequestExecutor(
                threadCount, queueCapacity, "watch-event-http-");
    }

    @Override
    public int execute(ApprovedDeliveryRequest request, Duration remainingTime)
            throws OutboundHttpFailure {
        HttpPost httpRequest = new HttpPost(request.endpoint().uri());
        return requestExecutor.execute(
                httpRequest,
                remainingTime,
                onResponseStarted -> executeBlocking(request, httpRequest, remainingTime, onResponseStarted));
    }

    @Override
    public void close() {
        requestExecutor.close();
    }

    private int executeBlocking(
            ApprovedDeliveryRequest delivery,
            HttpPost request,
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
                delivery.endpoint().hostname(), delivery.addresses(), clientLimits)) {
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + delivery.bearerToken());
            request.setHeader(IDEMPOTENCY_KEY, delivery.idempotencyKey());
            request.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
            request.setEntity(new ByteArrayEntity(delivery.payload(), ContentType.APPLICATION_JSON));

            return ApacheResponseLifecycle.execute(
                    client, HttpHost.create(delivery.endpoint().uri()), request, CloseMode.GRACEFUL, response -> {
                        onResponseStarted.run();
                        HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            bodyDiscarder.discard(entity, limits.maxResponseBytes());
                        }
                        return response.getCode();
                    });
        }
    }

}
