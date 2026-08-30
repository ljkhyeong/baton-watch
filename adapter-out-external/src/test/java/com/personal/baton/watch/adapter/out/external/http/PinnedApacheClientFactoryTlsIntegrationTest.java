package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PinnedApacheClientFactoryTlsIntegrationTest {

    private static final String CERTIFICATE_HOSTNAME = "watch.invalid";
    private static final String MISMATCHED_HOSTNAME = "mismatch.invalid";
    private static final String KEY_ALIAS = "pinned-host-server";
    private static final char[] KEYSTORE_PASSWORD = "baton-watch-test".toCharArray();
    private static final InetAddress LOOPBACK = ipv4Loopback();

    private final AtomicInteger handlerCalls = new AtomicInteger();
    private final AtomicReference<String> observedHost = new AtomicReference<>();
    private final AtomicReference<String> observedSni = new AtomicReference<>();

    private HttpsServer server;
    private PinnedApacheClientFactory clientFactory;

    @BeforeEach
    void startServer() throws Exception {
        KeyStore keyStore = loadServerKeyStore();
        SSLContext serverContext = serverContext(keyStore);
        clientFactory = new PinnedApacheClientFactory(clientContext(keyStore));
        server = HttpsServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters parameters) {
                SSLParameters sslParameters = serverContext.getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(false);
                parameters.setSSLParameters(sslParameters);
            }
        });
        server.createContext("/probe", exchange -> {
            HttpsExchange httpsExchange = (HttpsExchange) exchange;
            handlerCalls.incrementAndGet();
            observedHost.set(exchange.getRequestHeaders().getFirst(HttpHeaders.HOST));
            ExtendedSSLSession session = (ExtendedSSLSession) httpsExchange.getSSLSession();
            observedSni.set(requestedHostname(session.getRequestedServerNames()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void connectsToTheApprovedAddressWhilePreservingHostAndSni() throws Exception {
        int statusCode = execute(request(CERTIFICATE_HOSTNAME));

        assertEquals(204, statusCode);
        assertEquals(1, handlerCalls.get());
        assertEquals(CERTIFICATE_HOSTNAME + ":" + server.getAddress().getPort(), observedHost.get());
        assertEquals(CERTIFICATE_HOSTNAME, observedSni.get());
    }

    @Test
    void mapsATrustedCertificateHostnameMismatchToTlsFailure() {
        try (ApacheHttpRequestExecutor executor =
                new ApacheHttpRequestExecutor(1, 1, "test-pinned-tls-")) {
            HttpGet request = request(MISMATCHED_HOSTNAME);
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> executor.execute(
                            request, Duration.ofSeconds(5), ignored -> execute(request)));

            assertEquals(OutboundHttpFailure.Kind.TLS_FAILURE, failure.kind());
            assertEquals(0, failure.responseBytes());
            assertEquals(0, handlerCalls.get());
        }
    }

    private int execute(HttpGet request) throws IOException {
        String hostname = request.getAuthority().getHostName();
        try (CloseableHttpClient client = clientFactory.open(
                hostname, List.of(LOOPBACK), clientLimits())) {
            return ApacheResponseLifecycle.execute(
                    client, new HttpHost("https", hostname, server.getAddress().getPort()),
                    request, response -> response.getCode());
        }
    }

    private HttpGet request(String hostname) {
        return new HttpGet(URI.create(
                "https://" + hostname + ":" + server.getAddress().getPort() + "/probe"));
    }

    private static ApacheHttpClientLimits clientLimits() {
        return new ApacheHttpClientLimits(Duration.ofSeconds(2), Duration.ofSeconds(2), 100, 8_192);
    }

    private static KeyStore loadServerKeyStore() throws Exception {
        InputStream encoded = Objects.requireNonNull(
                PinnedApacheClientFactoryTlsIntegrationTest.class
                        .getResourceAsStream("/tls/pinned-host-server.p12.base64"),
                "TLS test key store resource");
        try (InputStream decoded = Base64.getMimeDecoder().wrap(encoded)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(decoded, KEYSTORE_PASSWORD);
            return keyStore;
        }
    }

    private static SSLContext serverContext(KeyStore keyStore)
            throws GeneralSecurityException {
        return SSLContexts.custom()
                .loadKeyMaterial(keyStore, KEYSTORE_PASSWORD)
                .build();
    }

    private static SSLContext clientContext(KeyStore serverKeyStore)
            throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry(
                KEY_ALIAS, Objects.requireNonNull(serverKeyStore.getCertificate(KEY_ALIAS)));
        return SSLContexts.custom()
                .loadTrustMaterial(trustStore, null)
                .build();
    }

    private static String requestedHostname(List<SNIServerName> serverNames) {
        return serverNames.stream()
                .filter(SNIHostName.class::isInstance)
                .map(SNIHostName.class::cast)
                .map(SNIHostName::getAsciiName)
                .findFirst()
                .orElseThrow(() -> new AssertionError("TLS request did not contain a DNS SNI name"));
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
