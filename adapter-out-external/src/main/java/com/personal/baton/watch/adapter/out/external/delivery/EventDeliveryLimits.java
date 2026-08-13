package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.time.Duration;
import java.util.Objects;

/** 단일 상태 변경 이벤트 전달 시도의 런타임 제한. */
public record EventDeliveryLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        long maxResponseBytes,
        int maxHeaderCount,
        int maxHeaderLineLength) {

    public EventDeliveryLimits {
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        responseTimeout = requirePositive(responseTimeout, "responseTimeout");
        totalTimeout = requirePositive(totalTimeout, "totalTimeout");
        requireNanosRepresentable(connectTimeout, "connectTimeout");
        requireNanosRepresentable(responseTimeout, "responseTimeout");
        requireNanosRepresentable(totalTimeout, "totalTimeout");
        OutboundResourceBounds.requireResponseBytes(
                maxResponseBytes, OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES);
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
