package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.http.ApacheHttpClientLimits;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpFailure;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpRequestExecutor;
import com.personal.baton.watch.adapter.out.external.http.ApacheResponseLifecycle;
import com.personal.baton.watch.adapter.out.external.http.PinnedApacheClientFactory;
import com.personal.baton.watch.adapter.out.external.http.ResponseBodyDiscarder;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;

/** Apache HttpClient 5 transport for one already-validated and DNS-pinned hop. */
public final class ApacheHttpHopTransport implements HttpHopTransport, AutoCloseable {

    private final CheckerLimits limits;
    private final ApacheHttpRequestExecutor requestExecutor;
    private final PinnedApacheClientFactory clientFactory;
    private final ResponseBodyDiscarder bodyDiscarder = new ResponseBodyDiscarder();

    public ApacheHttpHopTransport(CheckerLimits limits, int threadCount, int queueCapacity) {
        this(
                limits,
                new ApacheHttpRequestExecutor(threadCount, queueCapacity, "watch-http-"),
                new PinnedApacheClientFactory());
    }

    ApacheHttpHopTransport(
            CheckerLimits limits,
            ApacheHttpRequestExecutor requestExecutor,
            PinnedApacheClientFactory clientFactory) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    @Override
    public HttpHopResponse execute(ApprovedTarget target, Duration remainingTime, long remainingBytes)
            throws TransportFailure {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(remainingTime, "remainingTime");
        if (remainingTime.isZero() || remainingTime.isNegative()) {
            throw new TransportFailure(TransportFailure.Kind.CONNECT_TIMEOUT, 0);
        }
        if (remainingBytes < 0) {
            throw new TransportFailure(TransportFailure.Kind.RESPONSE_TOO_LARGE, 0);
        }

        try {
            return requestExecutor.execute(
                    remainingTime,
                    progress -> executeBlocking(
                            target, remainingTime, remainingBytes, progress));
        } catch (ApacheHttpFailure failure) {
            throw toTransportFailure(failure);
        }
    }

    @Override
    public void close() {
        requestExecutor.close();
    }

    private HttpHopResponse executeBlocking(
            ApprovedTarget target,
            Duration remainingTime,
            long remainingBytes,
            ApacheHttpRequestExecutor.Progress progress)
            throws IOException {
        ApacheHttpClientLimits clientLimits = ApacheHttpClientLimits.cappedBy(
                limits.connectTimeout(),
                limits.responseTimeout(),
                remainingTime,
                limits.maxHeaderCount(),
                limits.maxHeaderLineLength());

        try (CloseableHttpClient client = clientFactory.open(
                target.target().hostname(), target.addresses(), clientLimits)) {
            HttpGet request = new HttpGet(target.target().uri());
            request.setHeader(HttpHeaders.CONNECTION, "close");
            return ApacheResponseLifecycle.execute(
                    client, HttpHost.create(target.target().uri()), request, response -> {
                        progress.responseStarted();
                        List<String> locations = new ArrayList<>();
                        for (Header header : response.getHeaders(HttpHeaders.LOCATION)) {
                            locations.add(header.getValue() == null ? "" : header.getValue());
                        }
                        long responseBytes = 0;
                        HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            responseBytes = bodyDiscarder.discard(
                                    entity, remainingBytes, progress::responseBytes);
                        }
                        return new HttpHopResponse(
                                response.getCode(), locations, responseBytes);
                    });
        }
    }

    private static TransportFailure toTransportFailure(ApacheHttpFailure failure) {
        TransportFailure.Kind kind = switch (failure.kind()) {
            case CONNECT_TIMEOUT -> TransportFailure.Kind.CONNECT_TIMEOUT;
            case READ_TIMEOUT -> TransportFailure.Kind.READ_TIMEOUT;
            case TLS_FAILURE -> TransportFailure.Kind.TLS_FAILURE;
            case RESPONSE_TOO_LARGE -> TransportFailure.Kind.RESPONSE_TOO_LARGE;
            case NETWORK_FAILURE -> TransportFailure.Kind.NETWORK_FAILURE;
            case INTERNAL_FAILURE -> TransportFailure.Kind.INTERNAL_FAILURE;
        };
        return new TransportFailure(kind, failure.responseBytes());
    }
}
