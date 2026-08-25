package com.personal.baton.watch.bootstrap;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
        requireWholeSeconds(queryTimeout, "queryTimeout");
        requireWholeSeconds(transactionTimeout, "transactionTimeout");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        if (!lockTimeout.isPositive()
                || !lockTimeout.truncatedTo(ChronoUnit.MILLIS).equals(lockTimeout)) {
            throw new IllegalArgumentException("lockTimeout must be a positive whole-millisecond duration");
        }
        if (lockTimeout.compareTo(transactionTimeout) >= 0) {
            throw new IllegalArgumentException("lockTimeout must be shorter than transactionTimeout");
        }
    }

    private static void requireWholeSeconds(Duration value, String name) {
        Objects.requireNonNull(value, name);
        long seconds = value.getSeconds();
        if (seconds < 1 || value.getNano() != 0 || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    name + " must be a whole-second duration between 1 and 30 seconds");
        }
    }
}
