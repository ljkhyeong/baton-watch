package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.check.PinnedDnsResolver;
import com.personal.baton.watch.adapter.out.external.check.ResponseBodyDiscarder;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/** Executes one already-validated, DNS-pinned POST without redirects or client state. */
final class ApacheEventDeliveryTransport implements DeliveryTransport, AutoCloseable {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private enum Phase {
        CONNECTING,
        READING
    }

    private final EventDeliveryLimits limits;
    private final ExecutorService requestExecutor;
    private final ResponseBodyDiscarder bodyDiscarder = new ResponseBodyDiscarder();

    ApacheEventDeliveryTransport(EventDeliveryLimits limits, int threadCount, int queueCapacity) {
        this(limits, createExecutor(threadCount, queueCapacity));
    }

    ApacheEventDeliveryTransport(EventDeliveryLimits limits, ExecutorService requestExecutor) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
    }

    @Override
    public DeliveryHttpResponse execute(ApprovedDeliveryRequest request, Duration remainingTime)
            throws DeliveryTransportFailure {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(remainingTime, "remainingTime");
        if (remainingTime.isZero() || remainingTime.isNegative()) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.CONNECT_TIMEOUT);
        }

        AtomicReference<Phase> phase = new AtomicReference<>(Phase.CONNECTING);
        Future<DeliveryHttpResponse> future;
        try {
            future = requestExecutor.submit(() -> executeBlocking(request, remainingTime, phase));
        } catch (RejectedExecutionException exception) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.INTERNAL_FAILURE);
        }

        try {
            return future.get(remainingTime.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            DeliveryTransportFailure.Kind kind = phase.get() == Phase.CONNECTING
                    ? DeliveryTransportFailure.Kind.CONNECT_TIMEOUT
                    : DeliveryTransportFailure.Kind.READ_TIMEOUT;
            throw new DeliveryTransportFailure(kind);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.INTERNAL_FAILURE);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof DeliveryTransportFailure failure) {
                throw failure;
            }
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.INTERNAL_FAILURE);
        }
    }

    @Override
    public void close() {
        requestExecutor.shutdownNow();
    }

    private DeliveryHttpResponse executeBlocking(
            ApprovedDeliveryRequest delivery,
            Duration remainingTime,
            AtomicReference<Phase> phase)
            throws DeliveryTransportFailure {
        Duration connectTimeout = min(limits.connectTimeout(), remainingTime);
        Duration responseTimeout = min(limits.responseTimeout(), remainingTime);
        Timeout apacheConnectTimeout = Timeout.of(connectTimeout);
        Timeout apacheResponseTimeout = Timeout.of(responseTimeout);

        Http1Config http1Config = Http1Config.custom()
                .setMaxHeaderCount(limits.maxHeaderCount())
                .setMaxLineLength(limits.maxHeaderLineLength())
                .build();
        ManagedHttpClientConnectionFactory connectionFactory =
                ManagedHttpClientConnectionFactory.builder().http1Config(http1Config).build();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(apacheConnectTimeout)
                .setSocketTimeout(apacheResponseTimeout)
                .build();
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setConnectionFactory(connectionFactory)
                        .setDnsResolver(new PinnedDnsResolver(
                                delivery.endpoint().hostname(), delivery.addresses()))
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnTotal(1)
                        .setMaxConnPerRoute(1)
                        .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setAuthenticationEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setExpectContinueEnabled(false)
                .setHardCancellationEnabled(true)
                .setConnectionRequestTimeout(apacheConnectTimeout)
                .setResponseTimeout(apacheResponseTimeout)
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .disableConnectionState()
                .disableDefaultUserAgent()
                .build()) {
            HttpPost request = new HttpPost(delivery.endpoint().uri());
            request.setConfig(requestConfig);
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + delivery.bearerToken());
            request.setHeader(IDEMPOTENCY_KEY, delivery.idempotencyKey());
            request.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
            request.setHeader(HttpHeaders.CONNECTION, "close");
            request.setEntity(new ByteArrayEntity(delivery.payload(), ContentType.APPLICATION_JSON));

            return client.execute(request, response -> {
                phase.set(Phase.READING);
                long responseBytes = 0;
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    responseBytes = bodyDiscarder.discard(
                            entity, limits.maxResponseBytes(), ignored -> {});
                }
                return new DeliveryHttpResponse(response.getCode(), responseBytes);
            });
        } catch (ResponseBodyDiscarder.ResponseTooLargeException exception) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.RESPONSE_TOO_LARGE);
        } catch (ConnectTimeoutException | ConnectionRequestTimeoutException exception) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.CONNECT_TIMEOUT);
        } catch (SSLException exception) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.TLS_FAILURE);
        } catch (SocketTimeoutException exception) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.READ_TIMEOUT);
        } catch (UnknownHostException exception) {
            // A pinned resolver mismatch is an adapter invariant failure, not a fresh lookup.
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.INTERNAL_FAILURE);
        } catch (InterruptedIOException exception) {
            Thread.currentThread().interrupt();
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.INTERNAL_FAILURE);
        } catch (IOException exception) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.NETWORK_FAILURE);
        } catch (RuntimeException exception) {
            throw new DeliveryTransportFailure(DeliveryTransportFailure.Kind.INTERNAL_FAILURE);
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static ExecutorService createExecutor(int threadCount, int queueCapacity) {
        if (threadCount <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("event HTTP executor bounds must be positive");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "watch-event-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
