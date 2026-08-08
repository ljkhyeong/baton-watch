package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.Test;

class ApacheResponseLifecycleTest {

    private static final HttpHost TARGET = HttpHost.create(URI.create("https://example.com"));

    @Test
    void closesAFullyHandledResponseNormally() throws Exception {
        AtomicReference<CloseMode> closeMode = new AtomicReference<>();
        StubHttpClient client = client(closeMode);
        HttpGet request = new HttpGet("https://example.com/");

        String result = ApacheResponseLifecycle.execute(
                client, TARGET, request, ignored -> "handled");

        assertEquals("handled", result);
        assertEquals(CloseMode.GRACEFUL, closeMode.get());
    }

    @Test
    void immediatelyAbortsAFailedResponseWithoutGracefulDrain() {
        AtomicReference<CloseMode> closeMode = new AtomicReference<>();
        StubHttpClient client = client(closeMode);
        HttpGet request = new HttpGet("https://example.com/");

        assertThrows(
                ResponseBodyDiscarder.ResponseTooLargeException.class,
                () -> ApacheResponseLifecycle.execute(client, TARGET, request, ignored -> {
                    throw new ResponseBodyDiscarder.ResponseTooLargeException(8);
                }));

        assertEquals(CloseMode.IMMEDIATE, closeMode.get());
    }

    private static StubHttpClient client(AtomicReference<CloseMode> closeMode) {
        CloseableHttpResponse response = CloseableHttpResponse.create(
                new BasicClassicHttpResponse(200),
                (ignored, mode) -> closeMode.set(mode));
        return new StubHttpClient(response);
    }

    private static final class StubHttpClient extends CloseableHttpClient {

        private final CloseableHttpResponse response;

        private StubHttpClient(CloseableHttpResponse response) {
            this.response = response;
        }

        @Override
        protected CloseableHttpResponse doExecute(
                HttpHost target, ClassicHttpRequest request, HttpContext context) {
            return response;
        }

        @Override
        public void close(CloseMode closeMode) {}

        @Override
        public void close() throws IOException {}
    }
}
