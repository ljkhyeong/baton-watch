package com.personal.baton.watch.bootstrap;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 모든 짧은 PostgreSQL 영속성 트랜잭션과 행 잠금 대기를 제한한다. */
@ConfigurationProperties("watch.persistence")
public record PersistenceProperties(Duration transactionTimeout, Duration lockTimeout) {

    public PersistenceProperties {
        transactionTimeout = requireWholeSeconds(transactionTimeout);
        lockTimeout = requireWholeMilliseconds(lockTimeout);
        if (lockTimeout.compareTo(transactionTimeout) >= 0) {
            throw new IllegalArgumentException("lockTimeout must be shorter than transactionTimeout");
        }
    }

    int transactionTimeoutSeconds() {
        return Math.toIntExact(transactionTimeout.getSeconds());
    }

    private static Duration requireWholeSeconds(Duration value) {
        Objects.requireNonNull(value, "transactionTimeout");
        long seconds = value.getSeconds();
        if (seconds < 1 || value.getNano() != 0 || seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "transactionTimeout must be a positive whole-second duration in the Spring timeout range");
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
