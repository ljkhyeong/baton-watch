package com.personal.baton.watch.adapter.out.external.delivery;

import java.time.Duration;
import java.util.Objects;

/** Runtime bounds for one health-change event delivery attempt. */
public record EventDeliveryLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        long maxResponseBytes,
        int maxHeaderCount,
        int maxHeaderLineLength) {

    public static final EventDeliveryLimits DEFAULTS = new EventDeliveryLimits(
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            8L * 1024L,
            100,
            8 * 1024);

    public EventDeliveryLimits {
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        responseTimeout = requirePositive(responseTimeout, "responseTimeout");
        totalTimeout = requirePositive(totalTimeout, "totalTimeout");
        requireNanosRepresentable(connectTimeout, "connectTimeout");
        requireNanosRepresentable(responseTimeout, "responseTimeout");
        requireNanosRepresentable(totalTimeout, "totalTimeout");
        if (connectTimeout.compareTo(totalTimeout) > 0
                || responseTimeout.compareTo(totalTimeout) > 0) {
            throw new IllegalArgumentException("phase timeouts must not exceed totalTimeout");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        if (maxHeaderCount <= 0) {
            throw new IllegalArgumentException("maxHeaderCount must be positive");
        }
        if (maxHeaderLineLength <= 0) {
            throw new IllegalArgumentException("maxHeaderLineLength must be positive");
        }
    }

    long totalTimeoutNanos() {
        return totalTimeout.toNanos();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
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
