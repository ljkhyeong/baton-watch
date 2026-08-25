package com.personal.baton.watch.adapter.out.external.http;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.time.Duration;
import java.util.Objects;

/** 요청 범위 Apache 클라이언트 하나에 적용하는 연결 및 응답 제한. */
public record ApacheHttpClientLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        int maxHeaderCount,
        int maxHeaderLineLength) {

    public ApacheHttpClientLimits {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(responseTimeout, "responseTimeout");
        OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
    }

    public static ApacheHttpClientLimits cappedBy(
            Duration connectTimeout,
            Duration responseTimeout,
            Duration remainingTime,
            int maxHeaderCount,
            int maxHeaderLineLength) {
        return new ApacheHttpClientLimits(
                min(connectTimeout, remainingTime),
                min(responseTimeout, remainingTime),
                maxHeaderCount,
                maxHeaderLineLength);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
