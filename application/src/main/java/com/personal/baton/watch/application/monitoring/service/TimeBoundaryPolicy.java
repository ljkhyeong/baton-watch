package com.personal.baton.watch.application.monitoring.service;

import java.time.Duration;
import java.util.Objects;

/** 설정 가능한 시각 오프셋의 지원 범위를 한곳에서 소유한다. */
public final class TimeBoundaryPolicy {

    public static final long MAX_SUPPORTED_OFFSET_DAYS = 365;
    public static final Duration MAX_SUPPORTED_OFFSET = Duration.ofDays(MAX_SUPPORTED_OFFSET_DAYS);
    public static final Duration MAX_EVENT_DELIVERY_RETRY_DELAY = Duration.ofDays(30);

    private TimeBoundaryPolicy() {
    }

    public static Duration requireSupportedOffset(Duration duration, String name) {
        return requireBounded(duration, name, MAX_SUPPORTED_OFFSET);
    }

    public static Duration requireEventDeliveryRetryDelay(Duration duration, String name) {
        return requireBounded(duration, name, MAX_EVENT_DELIVERY_RETRY_DELAY);
    }

    private static Duration requireBounded(Duration duration, String name, Duration maximum) {
        Objects.requireNonNull(duration, name);
        if (!duration.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        if (duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " must not exceed " + maximum.toDays() + " days");
        }
        return duration;
    }
}
