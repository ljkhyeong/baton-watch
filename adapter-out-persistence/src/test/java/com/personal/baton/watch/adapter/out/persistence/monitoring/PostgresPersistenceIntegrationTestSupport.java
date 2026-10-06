package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 JVM마다 한 번 시작한 PostgreSQL을 모든 하위 시험 클래스가 공유한다.
 *
 * <p>종료는 Testcontainers Ryuk(비활성이면 JVM 종료 훅)에 맡긴다. 클래스마다 다시 시작하는
 * {@code @Container}는 쓰지 않는다. 시험마다 같은 DB를 clean하므로 병렬 실행을 켜면 잠금을 다시 검토한다.
 */
abstract class PostgresPersistenceIntegrationTestSupport {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(Objects.requireNonNull(
                            System.getProperty("watch.test.postgres-image"),
                            "Gradle 테스트 태스크로 실행해야 합니다: watch.test.postgres-image 없음"))
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("baton_watch")
            .withUsername("baton_watch")
            .withPassword("integration-test");
    protected static final Instant BASE_TIME = Instant.parse("2026-08-01T00:00:00Z");
    protected static final long CONCURRENCY_TIMEOUT_SECONDS = 10;

    static {
        POSTGRES.start();
    }

    protected JdbcTemplate jdbc;
    protected DataSource testDataSource;

    @BeforeEach
    void migrateFreshDatabase() {
        testDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(testDataSource);
        Flyway flyway = Flyway.configure()
                .dataSource(testDataSource)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    protected TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new DataSourceTransactionManager(testDataSource));
    }

    /** 운영 DB 시계 어댑터로 읽는다. 이를 쓰는 리스 시각 경계 시험이 어댑터 값의 범위도 함께 확인한다. */
    protected Instant databaseClock() {
        return new JdbcDatabaseClockAdapter(JdbcClient.create(jdbc)).currentTime();
    }

    /** 운영 연결처럼 시작 매개변수로 잠금 대기 상한을 건 별도 연결 원본을 만든다. */
    protected static DataSource lockTimeoutDataSource(Duration lockTimeout) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Properties properties = new Properties();
        properties.setProperty("options", "-c lock_timeout=" + lockTimeout.toMillis() + "ms");
        dataSource.setConnectionProperties(properties);
        return dataSource;
    }

    /** 두 작업을 동시에 출발시키고 결과를 제출 순서대로 반환한다. */
    protected static <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    awaitLatch(start);
                    return task.call();
                }));
            }
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            futures.forEach(PostgresPersistenceIntegrationTestSupport::cancelIfRunning);
            shutdownAndAwait(executor);
        }
    }

    /** 현재 스레드의 트랜잭션이 행 잠금을 쥔 동안 다른 스레드의 작업이 기다리지 않고 끝나는지 확인한다. */
    protected <T> T callWhileLocked(Runnable lock, Callable<T> competing) throws Exception {
        DataSourceTransactionManager lockTransactionManager =
                new DataSourceTransactionManager(testDataSource);
        TransactionStatus lockTransaction = lockTransactionManager.getTransaction(
                new DefaultTransactionDefinition());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = null;
        try {
            lock.run();
            future = executor.submit(competing);
            return future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            try {
                cancelIfRunning(future);
                lockTransactionManager.rollback(lockTransaction);
            } finally {
                shutdownAndAwait(executor);
            }
        }
    }

    /** 다른 스레드의 트랜잭션이 잠금을 쥔 동안 현재 스레드에서 본문을 실행한다. */
    protected void withLockHeld(Runnable lock, Runnable body) throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = null;
        try {
            holder = executor.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                lock.run();
                lockAcquired.countDown();
                awaitLatch(releaseLock);
            }));
            assertThat(lockAcquired.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            body.run();
        } finally {
            releaseLock.countDown();
            if (holder != null) {
                holder.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            shutdownAndAwait(executor);
        }
    }

    protected static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out while coordinating database transactions");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("database concurrency test was interrupted", exception);
        }
    }

    protected static void cancelIfRunning(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    protected static void shutdownAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(
                CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
}
