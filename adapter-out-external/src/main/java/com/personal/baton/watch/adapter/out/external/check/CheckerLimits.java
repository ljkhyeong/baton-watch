package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.time.Duration;
import java.util.Objects;

/** 단일 아웃바운드 URL 점검의 런타임 제한. 어떤 제한도 비활성화할 수 없다. */
public record CheckerLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        long maxResponseBytes,
        int maxRedirects,
        int maxHeaderCount,
        int maxHeaderLineLength) {

    public CheckerLimits {
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        responseTimeout = requirePositive(responseTimeout, "responseTimeout");
        totalTimeout = requirePositive(totalTimeout, "totalTimeout");
        requireNanosRepresentable(connectTimeout, "connectTimeout");
        requireNanosRepresentable(responseTimeout, "responseTimeout");
        requireNanosRepresentable(totalTimeout, "totalTimeout");
        OutboundResourceBounds.requireResponseBytes(
                maxResponseBytes, OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES);
        if (maxRedirects < 0 || maxRedirects > 3) {
            throw new IllegalArgumentException("maxRedirects must be between zero and three");
        }
        OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
    }

    long totalTimeoutNanos() {
        return totalTimeout.toNanos();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static void requireNanosRepresentable(Duration value, String name) {
        try {
            value.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large");
        }
    }
}
