package com.personal.baton.watch.adapter.out.external.http;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.time.Duration;
import java.util.Objects;

/** Connection and response bounds applied to one request-scoped Apache client. */
public record ApacheHttpClientLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        int maxHeaderCount,
        int maxHeaderLineLength) {

    public ApacheHttpClientLimits {
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        responseTimeout = requirePositive(responseTimeout, "responseTimeout");
        OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
    }

    public static ApacheHttpClientLimits cappedBy(
            Duration connectTimeout,
            Duration responseTimeout,
            Duration remainingTime,
            int maxHeaderCount,
            int maxHeaderLineLength) {
        Duration deadline = requirePositive(remainingTime, "remainingTime");
        return new ApacheHttpClientLimits(
                min(connectTimeout, deadline),
                min(responseTimeout, deadline),
                maxHeaderCount,
                maxHeaderLineLength);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
