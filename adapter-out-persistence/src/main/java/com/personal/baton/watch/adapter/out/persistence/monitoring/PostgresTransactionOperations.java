package com.personal.baton.watch.adapter.out.persistence.monitoring;

import java.util.Objects;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 영속성 작업이 기존 트랜잭션에 합류하지 않고 별도 짧은 트랜잭션에서 실행되게 한다. */
public final class PostgresTransactionOperations implements TransactionOperations {

    private final TransactionOperations transactions;

    public PostgresTransactionOperations(TransactionOperations transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "WATCH persistence must not join an existing transaction");
        }
        return transactions.execute(action);
    }
}
