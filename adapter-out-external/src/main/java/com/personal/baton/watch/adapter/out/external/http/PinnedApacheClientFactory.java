package com.personal.baton.watch.adapter.out.external.http;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;

/** Builds a request-scoped client that can resolve only one approved hostname. */
public final class PinnedApacheClientFactory {

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
                .setRedirectsEnabled(false)
                .setAuthenticationEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setExpectContinueEnabled(false)
                .setHardCancellationEnabled(true)
                .setConnectionRequestTimeout(connectTimeout)
                .setResponseTimeout(responseTimeout)
                .build();
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setConnectionFactory(connectionFactory)
                        .setDnsResolver(new PinnedDnsResolver(hostname, approvedAddresses))
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnTotal(1)
                        .setMaxConnPerRoute(1)
                        .build();

        try {
            return HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setConnectionManagerShared(false)
                    .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
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
