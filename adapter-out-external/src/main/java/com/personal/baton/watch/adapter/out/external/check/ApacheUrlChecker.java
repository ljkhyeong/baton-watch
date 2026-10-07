package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.port.out.UrlChecker;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** 홉마다 DNS 조회·주소 승인·리다이렉트 재검증을 하는 운영용 점검기. 제한된 DNS 및 HTTP 실행기를 소유한다. */
public final class ApacheUrlChecker implements UrlChecker, AutoCloseable {

    private final CheckerLimits limits;
    private final TargetUriPolicy targetPolicy = new TargetUriPolicy();
    private final GlobalAddressPolicy addressPolicy = new GlobalAddressPolicy();
    private final DnsLookup dnsLookup;
    private final HttpHopTransport transport;
    private final LongSupplier clock;

    public ApacheUrlChecker(
            CheckerLimits limits,
            int dnsThreadCount,
            int dnsQueueCapacity,
            int httpThreadCount,
            int httpQueueCapacity) {
        this(
                limits,
                new BoundedDnsLookup(dnsThreadCount, dnsQueueCapacity),
                new ApacheHttpHopTransport(limits, httpThreadCount, httpQueueCapacity),
                System::nanoTime);
    }

    ApacheUrlChecker(
            CheckerLimits limits,
            DnsLookup dnsLookup,
            HttpHopTransport transport,
            LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.dnsLookup = Objects.requireNonNull(dnsLookup, "dnsLookup");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CheckObservation check(TargetUrl targetUrl) {
        long startedAt = clock.getAsLong();
        int redirectCount = 0;
        try {
            ValidatedUri current = targetPolicy.prepare(targetUrl);

            Set<String> visited = new HashSet<>();
            visited.add(current.loopKey());

            while (true) {
                Duration remaining = remaining(startedAt);
                if (remaining.isZero()) {
                    return failure(CheckOutcome.CONNECT_TIMEOUT, startedAt, redirectCount);
                }

                List<InetAddress> approved =
                        addressPolicy.approve(dnsLookup.resolve(current.hostname(), remaining));

                remaining = remaining(startedAt);
                if (remaining.isZero()) {
                    return failure(CheckOutcome.DNS_FAILURE, startedAt, redirectCount);
                }

                HttpHopResponse response = transport.execute(new ApprovedTarget(current, approved), remaining);

                if (!isRedirectStatus(response.statusCode())) {
                    return finalResponse(response.statusCode(), startedAt, redirectCount);
                }
                if (response.locations().size() != 1) {
                    return failure(CheckOutcome.REDIRECT_REJECTED, startedAt, redirectCount);
                }
                if (redirectCount >= CheckObservation.MAX_REDIRECT_COUNT) {
                    return failure(CheckOutcome.TOO_MANY_REDIRECTS, startedAt, redirectCount);
                }

                ValidatedUri next;
                try {
                    URI redirectUri = targetPolicy.resolveRedirect(current, response.locations().getFirst());
                    next = targetPolicy.prepare(new TargetUrl(redirectUri.toString()));
                } catch (IllegalArgumentException exception) {
                    return failure(CheckOutcome.REDIRECT_REJECTED, startedAt, redirectCount);
                }
                if (current.scheme().equals("https") && next.scheme().equals("http")) {
                    return failure(CheckOutcome.REDIRECT_REJECTED, startedAt, redirectCount);
                }
                if (!visited.add(next.loopKey())) {
                    return failure(CheckOutcome.REDIRECT_REJECTED, startedAt, redirectCount);
                }

                redirectCount++;
                current = next;
            }
        } catch (OutboundHttpFailure exception) {
            return failure(outcome(exception.kind()), startedAt, redirectCount);
        } catch (RuntimeException exception) {
            return failure(CheckOutcome.INTERNAL_FAILURE, startedAt, redirectCount);
        }
    }

    @Override
    public void close() {
        try (dnsLookup; transport) {
            // 등록 역순인 전송 계층, DNS 조회기 순서로 닫는다.
        } catch (RuntimeException ignored) {
            // 종료는 최선을 다해 시도하며 예외 세부 정보를 의도적으로 노출하지 않는다.
        }
    }

    private CheckObservation finalResponse(int status, long startedAt, int redirectCount) {
        if (status < 200 || status > 599) {
            return failure(CheckOutcome.NETWORK_FAILURE, startedAt, redirectCount);
        }
        return CheckObservation.forHttpStatus(
                status, elapsed(startedAt), redirectCount);
    }

    private static CheckOutcome outcome(OutboundHttpFailure.Kind kind) {
        return switch (kind) {
            case DESTINATION_REJECTED -> CheckOutcome.DESTINATION_REJECTED;
            case DNS_FAILURE -> CheckOutcome.DNS_FAILURE;
            case CONNECT_TIMEOUT -> CheckOutcome.CONNECT_TIMEOUT;
            case READ_TIMEOUT -> CheckOutcome.READ_TIMEOUT;
            case TLS_FAILURE -> CheckOutcome.TLS_FAILURE;
            case RESPONSE_TOO_LARGE -> CheckOutcome.RESPONSE_TOO_LARGE;
            case NETWORK_FAILURE -> CheckOutcome.NETWORK_FAILURE;
            case INTERNAL_FAILURE -> CheckOutcome.INTERNAL_FAILURE;
        };
    }

    private CheckObservation failure(CheckOutcome outcome, long startedAt, int redirectCount) {
        return CheckObservation.failure(
                outcome, elapsed(startedAt), redirectCount);
    }

    private Duration remaining(long startedAt) {
        long elapsed = nonNegativeElapsed(startedAt);
        long remaining = limits.totalTimeout().toNanos() - elapsed;
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(nonNegativeElapsed(startedAt));
    }

    private long nonNegativeElapsed(long startedAt) {
        return Math.max(0, clock.getAsLong() - startedAt);
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }
}
