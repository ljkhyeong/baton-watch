package com.personal.baton.watch.adapter.out.external.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 외부 통신 테스트용 루프백 HTTP 서버. 테스트별 경로를 추가할 수 있고, 기본 경로를 제공한다.
 * `/stream`은 읽기 제한보다 짧은 간격으로 응답을 보내 전체 시간 제한의 실제 소켓 취소를 확인하고,
 * `/quick`은 요청 본문을 비운 뒤 204를 반환한다. 처리기 스레드 2개로 지연 응답 중 후속 요청을 받는다.
 */
public final class LoopbackHttpTestServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService handlers = Executors.newFixedThreadPool(
            2, Thread.ofPlatform().daemon().name("test-loopback-http-", 1).factory());
    private final CountDownLatch disconnected = new CountDownLatch(1);

    public LoopbackHttpTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.setExecutor(handlers);
        handle("/stream", this::stream);
        handle("/quick", exchange -> {
            try (exchange) {
                exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
    }

    public void handle(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    public URI uri(String hostname, String path) {
        return URI.create("http://" + hostname + ":" + server.getAddress().getPort() + path);
    }

    public boolean awaitDisconnected() throws InterruptedException {
        return disconnected.await(3, TimeUnit.SECONDS);
    }

    private void stream(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
            exchange.sendResponseHeaders(200, 0);
            while (!Thread.currentThread().isInterrupted()) {
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                Thread.sleep(25);
            }
        } catch (IOException exception) {
            disconnected.countDown();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        handlers.shutdownNow();
    }
}
