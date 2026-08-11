package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.http.ApacheHttpClientLimits;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpFailure;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpRequestExecutor;
import com.personal.baton.watch.adapter.out.external.http.ApacheResponseLifecycle;
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
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;

/** Executes one already-validated, DNS-pinned POST without redirects or client state. */
final class ApacheEventDeliveryTransport implements DeliveryTransport, AutoCloseable {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final EventDeliveryLimits limits;
    private final ApacheHttpRequestExecutor requestExecutor;
    private final PinnedApacheClientFactory clientFactory;
    private final ResponseBodyDiscarder bodyDiscarder = new ResponseBodyDiscarder();

    ApacheEventDeliveryTransport(EventDeliveryLimits limits, int threadCount, int queueCapacity) {
        this(
                limits,
                new ApacheHttpRequestExecutor(threadCount, queueCapacity, "watch-event-http-"),
                new PinnedApacheClientFactory());
    }

    ApacheEventDeliveryTransport(
            EventDeliveryLimits limits,
            ApacheHttpRequestExecutor requestExecutor,
            PinnedApacheClientFactory clientFactory) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    @Override
    public int execute(ApprovedDeliveryRequest request, Duration remainingTime)
            throws DeliveryTransportFailure {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(remainingTime, "remainingTime");
        if (!remainingTime.isPositive()) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.CONNECT_TIMEOUT);
        }

        try {
            return requestExecutor.execute(
                    remainingTime,
                    progress -> executeBlocking(request, remainingTime, progress));
        } catch (ApacheHttpFailure failure) {
            throw toTransportFailure(failure);
        }
    }

    @Override
    public void close() {
        requestExecutor.close();
    }

    private int executeBlocking(
            ApprovedDeliveryRequest delivery,
            Duration remainingTime,
            ApacheHttpRequestExecutor.Progress progress)
            throws IOException {
        ApacheHttpClientLimits clientLimits = ApacheHttpClientLimits.cappedBy(
                limits.connectTimeout(),
                limits.responseTimeout(),
                remainingTime,
                limits.maxHeaderCount(),
                limits.maxHeaderLineLength());

        try (CloseableHttpClient client = clientFactory.open(
                delivery.endpoint().hostname(), delivery.addresses(), clientLimits)) {
            HttpPost request = new HttpPost(delivery.endpoint().uri());
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + delivery.bearerToken());
            request.setHeader(IDEMPOTENCY_KEY, delivery.idempotencyKey());
            request.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
            request.setHeader(HttpHeaders.CONNECTION, "close");
            request.setEntity(new ByteArrayEntity(delivery.payload(), ContentType.APPLICATION_JSON));

            return ApacheResponseLifecycle.execute(
                    client, HttpHost.create(delivery.endpoint().uri()), request, response -> {
                        progress.responseStarted();
                        HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            bodyDiscarder.discard(
                                    entity,
                                    limits.maxResponseBytes(),
                                    progress::responseBytes);
                        }
                        return response.getCode();
                    });
        }
    }

    private static DeliveryTransportFailure toTransportFailure(ApacheHttpFailure failure) {
        DeliveryTransportFailure.Kind kind = switch (failure.kind()) {
            case CONNECT_TIMEOUT -> DeliveryTransportFailure.Kind.CONNECT_TIMEOUT;
            case READ_TIMEOUT -> DeliveryTransportFailure.Kind.READ_TIMEOUT;
            case TLS_FAILURE -> DeliveryTransportFailure.Kind.TLS_FAILURE;
            case RESPONSE_TOO_LARGE -> DeliveryTransportFailure.Kind.RESPONSE_TOO_LARGE;
            case NETWORK_FAILURE -> DeliveryTransportFailure.Kind.NETWORK_FAILURE;
            case INTERNAL_FAILURE -> DeliveryTransportFailure.Kind.INTERNAL_FAILURE;
        };
        return new DeliveryTransportFailure(kind);
    }
}
