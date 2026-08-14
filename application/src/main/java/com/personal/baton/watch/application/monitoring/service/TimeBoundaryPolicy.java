package com.personal.baton.watch.application.monitoring.service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 설정 가능한 시각 오프셋의 지원 범위와 안전한 {@link Instant} 산술을 한곳에서 소유한다. */
public final class TimeBoundaryPolicy {

    public static final Duration MAX_SUPPORTED_OFFSET = Duration.ofDays(365);
    public static final Duration MAX_EVENT_DELIVERY_RETRY_DELAY = Duration.ofDays(30);

    private TimeBoundaryPolicy() {
    }

    public static Duration requireSupportedOffset(Duration duration, String name) {
        return requireBounded(duration, name, MAX_SUPPORTED_OFFSET);
    }

    public static Duration requireEventDeliveryRetryDelay(Duration duration, String name) {
        return requireBounded(duration, name, MAX_EVENT_DELIVERY_RETRY_DELAY);
    }

    public static Instant add(Instant base, Duration offset, String name) {
        Objects.requireNonNull(base, "base");
        Duration supported = requireSupportedOffset(offset, name);
        try {
            return base.plus(supported);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException(name + " exceeds the supported timestamp range", exception);
        }
    }

    public static Instant subtract(Instant base, Duration offset, String name) {
        Objects.requireNonNull(base, "base");
        Duration supported = requireSupportedOffset(offset, name);
        try {
            return base.minus(supported);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException(name + " exceeds the supported timestamp range", exception);
        }
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
