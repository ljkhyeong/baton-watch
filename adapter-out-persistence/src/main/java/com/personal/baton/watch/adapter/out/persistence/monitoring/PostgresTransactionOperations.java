package com.personal.baton.watch.adapter.out.persistence.monitoring;

import java.time.Duration;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Applies a transaction-local PostgreSQL lock timeout around short persistence work. */
public final class PostgresTransactionOperations implements TransactionOperations {

    private static final String APPLY_LOCK_TIMEOUT =
            "SELECT set_config('lock_timeout', ?, true)";

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final String lockTimeoutSetting;

    public PostgresTransactionOperations(
            JdbcTemplate jdbc, TransactionOperations transactions, Duration lockTimeout) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.lockTimeoutSetting = setting(lockTimeout);
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "WATCH persistence must not join an existing transaction");
        }
        return transactions.execute(status -> {
            Objects.requireNonNull(
                    jdbc.queryForObject(APPLY_LOCK_TIMEOUT, String.class, lockTimeoutSetting),
                    "PostgreSQL lock timeout setting");
            return action.doInTransaction(status);
        });
    }

    private static String setting(Duration lockTimeout) {
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        long milliseconds;
        try {
            milliseconds = lockTimeout.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("lockTimeout is too large");
        }
        if (milliseconds < 1 || !Duration.ofMillis(milliseconds).equals(lockTimeout)) {
            throw new IllegalArgumentException("lockTimeout must be a positive whole-millisecond duration");
        }
        return milliseconds + "ms";
    }
}
