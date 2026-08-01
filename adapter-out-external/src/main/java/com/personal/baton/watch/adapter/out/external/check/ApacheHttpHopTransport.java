package com.personal.baton.watch.adapter.out.external.check;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;

/** Apache HttpClient 5 transport for one already-validated and DNS-pinned hop. */
public final class ApacheHttpHopTransport implements HttpHopTransport, AutoCloseable {

    private enum Phase {
        CONNECTING,
        READING
    }

    private final CheckerLimits limits;
    private final ExecutorService requestExecutor;
    private final ResponseBodyDiscarder bodyDiscarder = new ResponseBodyDiscarder();

    public ApacheHttpHopTransport(CheckerLimits limits, int threadCount, int queueCapacity) {
        this(limits, createExecutor(threadCount, queueCapacity));
    }

    ApacheHttpHopTransport(CheckerLimits limits, ExecutorService requestExecutor) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
    }

    @Override
    public HttpHopResponse execute(ApprovedTarget target, Duration remainingTime, long remainingBytes)
            throws TransportFailure {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(remainingTime, "remainingTime");
        if (remainingTime.isZero() || remainingTime.isNegative()) {
            throw new TransportFailure(TransportFailure.Kind.CONNECT_TIMEOUT, 0);
        }
        if (remainingBytes <= 0) {
            throw new TransportFailure(TransportFailure.Kind.RESPONSE_TOO_LARGE, 0);
        }

        AtomicReference<Phase> phase = new AtomicReference<>(Phase.CONNECTING);
        AtomicLong consumed = new AtomicLong();
        Future<HttpHopResponse> future;
        try {
            future = requestExecutor.submit(
                    () -> executeBlocking(target, remainingTime, remainingBytes, phase, consumed));
        } catch (RejectedExecutionException exception) {
            throw new TransportFailure(TransportFailure.Kind.INTERNAL_FAILURE, 0);
        }

        try {
            return future.get(remainingTime.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            TransportFailure.Kind kind = phase.get() == Phase.CONNECTING
                    ? TransportFailure.Kind.CONNECT_TIMEOUT
                    : TransportFailure.Kind.READ_TIMEOUT;
            throw new TransportFailure(kind, consumed.get());
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TransportFailure(TransportFailure.Kind.INTERNAL_FAILURE, consumed.get());
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof TransportFailure failure) {
                throw failure;
            }
            throw new TransportFailure(TransportFailure.Kind.INTERNAL_FAILURE, consumed.get());
        }
    }

    @Override
    public void close() {
        requestExecutor.shutdownNow();
    }

    private HttpHopResponse executeBlocking(
            ApprovedTarget target,
            Duration remainingTime,
            long remainingBytes,
            AtomicReference<Phase> phase,
            AtomicLong consumed)
            throws TransportFailure {
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
                                target.target().hostname(), target.addresses()))
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnTotal(1)
                        .setMaxConnPerRoute(1)
                        .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setAuthenticationEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
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
            HttpGet request = new HttpGet(target.target().uri());
            request.setConfig(requestConfig);
            request.setHeader(HttpHeaders.CONNECTION, "close");
            return client.execute(request, response -> {
                phase.set(Phase.READING);
                List<String> locations = new ArrayList<>();
                for (Header header : response.getHeaders(HttpHeaders.LOCATION)) {
                    locations.add(header.getValue() == null ? "" : header.getValue());
                }
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    bodyDiscarder.discard(entity, remainingBytes, consumed::set);
                }
                return new HttpHopResponse(response.getCode(), locations, consumed.get());
            });
        } catch (ResponseBodyDiscarder.ResponseTooLargeException exception) {
            throw new TransportFailure(
                    TransportFailure.Kind.RESPONSE_TOO_LARGE,
                    Math.max(consumed.get(), exception.consumedWithinLimit()));
        } catch (ConnectTimeoutException | ConnectionRequestTimeoutException exception) {
            throw new TransportFailure(TransportFailure.Kind.CONNECT_TIMEOUT, consumed.get());
        } catch (SSLException exception) {
            throw new TransportFailure(TransportFailure.Kind.TLS_FAILURE, consumed.get());
        } catch (SocketTimeoutException exception) {
            throw new TransportFailure(TransportFailure.Kind.READ_TIMEOUT, consumed.get());
        } catch (UnknownHostException exception) {
            // A pinned resolver mismatch indicates an adapter invariant failure, not a new DNS lookup failure.
            throw new TransportFailure(TransportFailure.Kind.INTERNAL_FAILURE, consumed.get());
        } catch (InterruptedIOException exception) {
            Thread.currentThread().interrupt();
            throw new TransportFailure(TransportFailure.Kind.INTERNAL_FAILURE, consumed.get());
        } catch (IOException exception) {
            throw new TransportFailure(TransportFailure.Kind.NETWORK_FAILURE, consumed.get());
        } catch (RuntimeException exception) {
            throw new TransportFailure(TransportFailure.Kind.INTERNAL_FAILURE, consumed.get());
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static ExecutorService createExecutor(int threadCount, int queueCapacity) {
        if (threadCount <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("HTTP executor bounds must be positive");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "watch-http-" + sequence.incrementAndGet());
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
