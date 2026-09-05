package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
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

final class SafeUrlCheckEngine {

    private final CheckerLimits limits;
    private final TargetUriPolicy targetPolicy;
    private final DnsLookup dnsLookup;
    private final GlobalAddressPolicy addressPolicy;
    private final HttpHopTransport transport;
    private final LongSupplier clock;

    SafeUrlCheckEngine(
            CheckerLimits limits,
            TargetUriPolicy targetPolicy,
            DnsLookup dnsLookup,
            GlobalAddressPolicy addressPolicy,
            HttpHopTransport transport,
            LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.targetPolicy = Objects.requireNonNull(targetPolicy, "targetPolicy");
        this.dnsLookup = Objects.requireNonNull(dnsLookup, "dnsLookup");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CheckObservation check(TargetUrl targetUrl) {
        long startedAt = clock.getAsLong();
        int redirectCount = 0;
        try {
            ValidatedUri current;
            try {
                current = targetPolicy.prepare(targetUrl);
            } catch (IllegalArgumentException exception) {
                return failure(CheckOutcome.DESTINATION_REJECTED, startedAt, redirectCount);
            }

            Set<String> visited = new HashSet<>();
            visited.add(current.loopKey());

            while (true) {
                Duration remaining = remaining(startedAt);
                if (remaining.isZero()) {
                    return failure(CheckOutcome.CONNECT_TIMEOUT, startedAt, redirectCount);
                }

                List<InetAddress> resolved;
                try {
                    resolved = dnsLookup.resolve(current.hostname(), remaining);
                } catch (DnsLookupException exception) {
                    return switch (exception.reason()) {
                        case DNS_FAILURE -> failure(CheckOutcome.DNS_FAILURE, startedAt, redirectCount);
                        case INTERNAL_FAILURE -> internalFailure(startedAt, redirectCount);
                    };
                }

                List<InetAddress> approved;
                try {
                    approved = addressPolicy.approve(resolved);
                } catch (AddressPolicyException exception) {
                    return failure(CheckOutcome.DESTINATION_REJECTED, startedAt, redirectCount);
                }

                remaining = remaining(startedAt);
                if (remaining.isZero()) {
                    return failure(CheckOutcome.DNS_FAILURE, startedAt, redirectCount);
                }

                HttpHopResponse response;
                try {
                    response = transport.execute(new ApprovedTarget(current, approved), remaining);
                } catch (OutboundHttpFailure exception) {
                    return transportFailure(exception.kind(), startedAt, redirectCount);
                }

                if (!isRedirectStatus(response.statusCode())) {
                    return finalResponse(response.statusCode(), startedAt, redirectCount);
                }
                if (response.locations().size() != 1) {
                    return failure(CheckOutcome.REDIRECT_REJECTED, startedAt, redirectCount);
                }
                if (redirectCount >= limits.maxRedirects()) {
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
        } catch (RuntimeException exception) {
            return internalFailure(startedAt, redirectCount);
        }
    }

    private CheckObservation finalResponse(int status, long startedAt, int redirectCount) {
        if (status < 200 || status > 599) {
            return failure(CheckOutcome.NETWORK_FAILURE, startedAt, redirectCount);
        }
        return CheckObservation.forHttpStatus(
                status, elapsed(startedAt), 0, redirectCount);
    }

    private CheckObservation transportFailure(
            OutboundHttpFailure.Kind kind, long startedAt, int redirectCount) {
        CheckOutcome outcome = switch (kind) {
            case CONNECT_TIMEOUT -> CheckOutcome.CONNECT_TIMEOUT;
            case READ_TIMEOUT -> CheckOutcome.READ_TIMEOUT;
            case TLS_FAILURE -> CheckOutcome.TLS_FAILURE;
            case RESPONSE_TOO_LARGE -> CheckOutcome.RESPONSE_TOO_LARGE;
            case NETWORK_FAILURE -> CheckOutcome.NETWORK_FAILURE;
            case INTERNAL_FAILURE -> CheckOutcome.INTERNAL_FAILURE;
        };
        return outcome == CheckOutcome.INTERNAL_FAILURE
                ? internalFailure(startedAt, redirectCount)
                : failure(outcome, startedAt, redirectCount);
    }

    private CheckObservation failure(CheckOutcome outcome, long startedAt, int redirectCount) {
        return CheckObservation.failure(
                outcome, elapsed(startedAt), 0, redirectCount);
    }

    private CheckObservation internalFailure(long startedAt, int redirectCount) {
        return new CheckObservation(
                CheckOutcome.INTERNAL_FAILURE,
                null,
                elapsed(startedAt),
                0,
                redirectCount);
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
