package com.personal.baton.watch.adapter.out.external.http;

import java.io.IOException;
import java.util.Objects;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;

/** Closes completed responses normally and aborts failed responses without draining them. */
public final class ApacheResponseLifecycle {

    @FunctionalInterface
    public interface Handler<T> {

        T handle(ClassicHttpResponse response) throws IOException;
    }

    private ApacheResponseLifecycle() {}

    public static <T> T execute(
            CloseableHttpClient client,
            HttpHost target,
            ClassicHttpRequest request,
            Handler<T> handler)
            throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");

        ClassicHttpResponse response = client.executeOpen(
                target, request, HttpClientContext.create());
        try {
            T result = handler.handle(response);
            response.close();
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            abort(response, failure);
            throw failure;
        }
    }

    private static void abort(ClassicHttpResponse response, Throwable failure) {
        if (response instanceof ModalCloseable closeable) {
            closeable.close(CloseMode.IMMEDIATE);
            return;
        }
        try {
            response.setEntity(null);
            response.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
