package com.personal.baton.watch.bootstrap;

import java.time.Duration;

/** 외부 작업 한 건과 직렬 배치가 스케줄러 종료 대기 안에서 끝나는지 검증한다. */
final class WorkerExecutionBudget {

    private WorkerExecutionBudget() {
    }

    static void requireSafe(
            String worker,
            Duration executionBudget,
            Duration leaseDuration,
            Duration totalTimeout,
            int batchSize,
            DatabaseRuntimeProperties database,
            PersistenceProperties persistence) {
        Duration connectionTimeout = Duration.ofMillis(database.connectionTimeoutMillis());
        Duration transactionTimeout = persistence.transactionTimeout();
        Duration leaseBudget;
        Duration batchBudget;
        try {
            leaseBudget = transactionTimeout
                    .multipliedBy(2)
                    .plus(connectionTimeout)
                    .plus(totalTimeout);
            Duration itemExecutionBudget = connectionTimeout
                    .plus(transactionTimeout)
                    .multipliedBy(2)
                    .plus(totalTimeout);
            batchBudget = itemExecutionBudget.multipliedBy(batchSize);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(worker + " execution budget is too large");
        }
        if (leaseDuration.compareTo(leaseBudget) <= 0) {
            throw new IllegalArgumentException(worker + " leaseDuration must exceed one item execution budget");
        }
        if (batchBudget.compareTo(executionBudget) > 0) {
            throw new IllegalArgumentException(worker + " batch must fit within workerExecutionBudget");
        }
    }
}
