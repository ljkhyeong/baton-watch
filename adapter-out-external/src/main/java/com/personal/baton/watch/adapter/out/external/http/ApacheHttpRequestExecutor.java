package com.personal.baton.watch.adapter.out.external.http;

import com.personal.baton.watch.adapter.out.external.BoundedTaskExecutor;
import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentTooLongException;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.IOFunction;

/** 제한된 HTTP 실행기와 IP 고정 클라이언트 생성을 소유하고 각 요청에 하나의 강제 기한을 적용한다. */
public final class ApacheHttpRequestExecutor implements AutoCloseable {

    private final BoundedTaskExecutor executor;
    private final PinnedApacheClientFactory clientFactory = new PinnedApacheClientFactory();

    public ApacheHttpRequestExecutor(
            int threadCount, int queueCapacity, String threadNamePrefix) {
        OutboundResourceBounds.requireRequestExecutorBounds(threadCount, queueCapacity);
        this.executor = new BoundedTaskExecutor(threadCount, queueCapacity, threadNamePrefix);
    }

    /**
     * 승인된 주소에 고정한 요청 범위 클라이언트 하나로 요청을 실행한다. 요청은 제출 전에 완성해 넘긴다.
     * 응답 헤더를 받은 뒤 처리기를 호출하고, 처리기가 성공하면 지정한 방식으로, 실패하면 즉시 응답을 닫는다.
     */
    public <T> T executePinned(
            HttpUriRequestBase request,
            List<InetAddress> approvedAddresses,
            ApacheHttpClientLimits limits,
            Duration remainingTime,
            CloseMode successCloseMode,
            IOFunction<ClassicHttpResponse, T> handler)
            throws OutboundHttpFailure {
        return execute(request, remainingTime, onResponseStarted -> {
            // 고정 리졸버 범위는 Host·SNI와 같은 요청 authority에서 정하고, 단계 제한은 팩터리가 남은 시간으로 줄인다.
            try (CloseableHttpClient client = clientFactory.open(
                    request.getAuthority().getHostName(), approvedAddresses, limits, remainingTime)) {
                return ApacheResponseLifecycle.execute(client, request, successCloseMode, response -> {
                    onResponseStarted.run();
                    return handler.apply(response);
                });
            }
        });
    }

    <T> T execute(HttpUriRequestBase request, Duration timeout, IOFunction<Runnable, T> operation)
            throws OutboundHttpFailure {
        AtomicBoolean responseStarted = new AtomicBoolean();
        try {
            // 플랫폼 스레드의 소켓 읽기는 인터럽트로 멈추지 않으므로 취소 시 요청 자체를 취소한다.
            return executor.call(
                    () -> executeBlocking(operation, () -> responseStarted.set(true)), timeout, request::cancel);
        } catch (TimeoutException exception) {
            throw new OutboundHttpFailure(responseStarted.get()
                    ? OutboundHttpFailure.Kind.READ_TIMEOUT
                    : OutboundHttpFailure.Kind.CONNECT_TIMEOUT);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof OutboundHttpFailure httpFailure) {
                throw httpFailure;
            }
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        } catch (CancellationException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        }
    }

    @Override
    public void close() {
        executor.close();
    }

    private static <T> T executeBlocking(
            IOFunction<Runnable, T> operation, Runnable onResponseStarted)
            throws OutboundHttpFailure {
        try {
            return operation.apply(onResponseStarted);
        } catch (ContentTooLongException | MessageConstraintException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE);
        } catch (ConnectTimeoutException | ConnectionRequestTimeoutException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.CONNECT_TIMEOUT);
        } catch (SSLException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.TLS_FAILURE);
        } catch (SocketTimeoutException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.READ_TIMEOUT);
        } catch (UnknownHostException exception) {
            // 고정 리졸버 불일치는 새로운 DNS 조회 사유가 아니라 어댑터 불변식 위반이다.
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        } catch (InterruptedIOException exception) {
            Thread.currentThread().interrupt();
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE);
        } catch (IOException exception) {
            throw new OutboundHttpFailure(OutboundHttpFailure.Kind.NETWORK_FAILURE);
        }
    }

}
