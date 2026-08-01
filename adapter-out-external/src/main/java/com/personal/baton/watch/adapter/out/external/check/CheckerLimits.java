package com.personal.baton.watch.adapter.out.external.check;

import java.time.Duration;
import java.util.Objects;

/** Runtime limits for a single outbound URL check. No limit may be disabled. */
public record CheckerLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        long maxResponseBytes,
        int maxRedirects,
        int maxHeaderCount,
        int maxHeaderLineLength) {

    public static final CheckerLimits DEFAULTS = new CheckerLimits(
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            64L * 1024L,
            3,
            100,
            8 * 1024);

    public CheckerLimits {
        connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        responseTimeout = requirePositive(responseTimeout, "responseTimeout");
        totalTimeout = requirePositive(totalTimeout, "totalTimeout");
        requireNanosRepresentable(connectTimeout, "connectTimeout");
        requireNanosRepresentable(responseTimeout, "responseTimeout");
        requireNanosRepresentable(totalTimeout, "totalTimeout");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        if (maxRedirects < 0 || maxRedirects > 3) {
            throw new IllegalArgumentException("maxRedirects must be between zero and three");
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
