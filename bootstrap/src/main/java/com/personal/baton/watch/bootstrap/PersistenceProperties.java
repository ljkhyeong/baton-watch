package com.personal.baton.watch.bootstrap;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 모든 짧은 PostgreSQL 영속성 트랜잭션과 행 잠금 대기를 제한한다. */
@ConfigurationProperties("watch.persistence")
public record PersistenceProperties(
        Duration queryTimeout,
        Duration transactionTimeout,
        Duration lockTimeout) {

    private static final long MAX_TIMEOUT_SECONDS = 30;

    public PersistenceProperties {
        queryTimeout = requireWholeSeconds(queryTimeout, "queryTimeout");
        transactionTimeout = requireWholeSeconds(transactionTimeout, "transactionTimeout");
        lockTimeout = requireWholeMilliseconds(lockTimeout);
        if (lockTimeout.compareTo(transactionTimeout) >= 0) {
            throw new IllegalArgumentException("lockTimeout must be shorter than transactionTimeout");
        }
    }

    int queryTimeoutSeconds() {
        return Math.toIntExact(queryTimeout.getSeconds());
    }

    int transactionTimeoutSeconds() {
        return Math.toIntExact(transactionTimeout.getSeconds());
    }

    private static Duration requireWholeSeconds(Duration value, String name) {
        Objects.requireNonNull(value, name);
        long seconds = value.getSeconds();
        if (seconds < 1 || value.getNano() != 0 || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    name + " must be a whole-second duration between 1 and 30 seconds");
        }
        return value;
    }

    private static Duration requireWholeMilliseconds(Duration value) {
        Objects.requireNonNull(value, "lockTimeout");
        long milliseconds;
        try {
            milliseconds = value.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("lockTimeout is too large");
        }
        if (milliseconds < 1 || !Duration.ofMillis(milliseconds).equals(value)) {
            throw new IllegalArgumentException("lockTimeout must be a positive whole-millisecond duration");
        }
        return value;
    }
}
