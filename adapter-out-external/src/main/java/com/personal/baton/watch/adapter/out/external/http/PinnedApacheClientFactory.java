package com.personal.baton.watch.adapter.out.external.http;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;

/** 승인된 호스트 이름 하나만 해석할 수 있는 요청 범위 클라이언트를 구성한다. */
final class PinnedApacheClientFactory {

    /**
     * 대상 점검과 콜백이 함께 보내는 고정 {@code User-Agent}다. 이 헤더가 없는 요청을 거부하는 사이트의
     * 거짓 실패를 막으며, 브라우저를 가장하거나 HTTP 클라이언트·JVM 버전을 드러내지 않는다.
     */
    static final String USER_AGENT = "baton-watch (+https://github.com/ljkhyeong/baton-watch)";
    private static final Duration MINIMUM_SOCKET_TIMEOUT = Duration.ofMillis(1);

    private final SSLContext sslContext;

    PinnedApacheClientFactory() {
        this(SSLContexts.createDefault());
    }

    PinnedApacheClientFactory(SSLContext sslContext) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
    }

    CloseableHttpClient open(
            String hostname,
            List<InetAddress> approvedAddresses,
            ApacheHttpClientLimits limits,
            Duration remainingTime) {
        Timeout connectTimeout = socketTimeout(limits.connectTimeout(), remainingTime);
        Timeout responseTimeout = socketTimeout(limits.responseTimeout(), remainingTime);
        Http1Config http1Config = Http1Config.custom()
                .setMaxHeaderCount(limits.maxHeaderCount())
                .setMaxLineLength(limits.maxHeaderLineLength())
                .build();
        ManagedHttpClientConnectionFactory connectionFactory =
                ManagedHttpClientConnectionFactory.builder().http1Config(http1Config).build();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(responseTimeout)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setAuthenticationEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setConnectionRequestTimeout(connectTimeout)
                .setResponseTimeout(responseTimeout)
                .build();
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setConnectionFactory(connectionFactory)
                        .setDnsResolver(new PinnedDnsResolver(hostname, approvedAddresses))
                        .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                                .setSslContext(sslContext)
                                .buildClassic())
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnTotal(1)
                        .setMaxConnPerRoute(1)
                        .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .disableConnectionState()
                .setUserAgent(USER_AGENT)
                .build();
    }

    /**
     * 단계 제한을 남은 전체 기한으로 줄인다. 소켓에서 0밀리초는 무제한이므로
     * 1밀리초 미만으로 잘린 양수 제한은 1밀리초로 올린다.
     */
    static Timeout socketTimeout(Duration limit, Duration remainingTime) {
        Duration capped = limit.compareTo(remainingTime) <= 0 ? limit : remainingTime;
        return Timeout.of(capped.compareTo(MINIMUM_SOCKET_TIMEOUT) < 0 ? MINIMUM_SOCKET_TIMEOUT : capped);
    }
}
