package com.personal.baton.watch.adapter.out.external.http;

import java.net.InetAddress;
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
public final class PinnedApacheClientFactory {

    private final SSLContext sslContext;

    public PinnedApacheClientFactory() {
        this(SSLContexts.createDefault());
    }

    PinnedApacheClientFactory(SSLContext sslContext) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
    }

    public CloseableHttpClient open(
            String hostname,
            List<InetAddress> approvedAddresses,
            ApacheHttpClientLimits limits) {
        Objects.requireNonNull(limits, "limits");

        Timeout connectTimeout = Timeout.of(limits.connectTimeout());
        Timeout responseTimeout = Timeout.of(limits.responseTimeout());
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

        try {
            return HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .disableAutomaticRetries()
                    .disableRedirectHandling()
                    .disableCookieManagement()
                    .disableAuthCaching()
                    .disableContentCompression()
                    .disableConnectionState()
                    .disableDefaultUserAgent()
                    .build();
        } catch (RuntimeException | Error exception) {
            connectionManager.close();
            throw exception;
        }
    }

}
