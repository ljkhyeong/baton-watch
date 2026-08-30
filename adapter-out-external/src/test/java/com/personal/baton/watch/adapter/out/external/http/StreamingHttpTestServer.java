package com.personal.baton.watch.adapter.out.external.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 읽기 제한보다 짧은 간격으로 응답을 보내 전체 시간 제한의 실제 소켓 취소를 확인한다. */
public final class StreamingHttpTestServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService handlers = Executors.newFixedThreadPool(
            2, Thread.ofPlatform().daemon().name("test-streaming-http-", 1).factory());
    private final CountDownLatch responseStarted = new CountDownLatch(1);
    private final CountDownLatch disconnected = new CountDownLatch(1);

    public StreamingHttpTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/stream", this::stream);
        server.createContext("/quick", exchange -> {
            try (exchange) {
                exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
    }

    public URI uri(String hostname, String path) {
        return URI.create("http://" + hostname + ":" + server.getAddress().getPort() + path);
    }

    public boolean awaitResponseStarted() throws InterruptedException {
        return responseStarted.await(3, TimeUnit.SECONDS);
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
                responseStarted.countDown();
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
