package com.personal.baton.watch.adapter.out.external.http;

import java.io.IOException;
import java.util.Objects;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.IOFunction;

/** 성공 시 호출자가 선택한 방식으로 닫고, 실패 시 본문을 끝까지 소비하지 않고 중단한다. */
public final class ApacheResponseLifecycle {

    private ApacheResponseLifecycle() {}

    public static <T> T execute(
            CloseableHttpClient client,
            HttpHost target,
            ClassicHttpRequest request,
            CloseMode successCloseMode,
            IOFunction<CloseableHttpResponse, T> handler)
            throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(successCloseMode, "successCloseMode");
        Objects.requireNonNull(handler, "handler");

        CloseableHttpResponse response = CloseableHttpResponse.adapt(
                client.executeOpen(target, request, HttpClientContext.create()));
        try {
            T result = handler.apply(response);
            response.close(successCloseMode);
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            response.close(CloseMode.IMMEDIATE);
            throw failure;
        }
    }
}
