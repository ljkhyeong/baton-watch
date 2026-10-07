package com.personal.baton.watch.adapter.out.external;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 상한이 있는 실행기에서 작업 하나를 기한 안에 실행하고, 취소하면 대기열에서 빼고 취소 훅을 호출한다.
 * 제출 거부·호출자 인터럽트·종료 취소는 {@link CancellationException}으로 알리며 호출자의 인터럽트 상태는 유지한다.
 * 스레드·대기열 상한 검증은 경계마다 다르므로 호출부가 생성 전에 한다.
 */
public final class BoundedTaskExecutor implements AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final Set<FutureTask<?>> pending = ConcurrentHashMap.newKeySet();

    public BoundedTaskExecutor(int threadCount, int queueCapacity, String threadNamePrefix) {
        this(new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().daemon().name(threadNamePrefix, 1).factory()));
    }

    BoundedTaskExecutor(ThreadPoolExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public <T> T call(Callable<T> task, Duration timeout, Runnable onCancel)
            throws TimeoutException, ExecutionException {
        if (Thread.currentThread().isInterrupted()) {
            onCancel.run();
            throw new CancellationException();
        }
        if (!timeout.isPositive()) {
            throw new TimeoutException();
        }
        // 작업을 제출하기 전에 기한을 계산해 기한 없이 실행되는 작업이 남지 않게 한다.
        long timeoutNanos = timeout.toNanos();

        FutureTask<T> future = new FutureTask<>(task) {
            @Override
            protected void done() {
                pending.remove(this);
                if (isCancelled()) {
                    // 작업자 인터럽트만으로는 플랫폼 스레드의 소켓 읽기를 중단할 수 없어 취소 훅을 호출한다.
                    onCancel.run();
                    // 취소한 대기 작업의 대기열 자리를 작업자가 끝나기 전에 바로 돌려준다.
                    executor.remove(this);
                }
            }
        };
        pending.add(future);
        try {
            executor.execute(future);
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException exception) {
            future.cancel(true);
            throw new CancellationException();
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw exception;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        pending.forEach(task -> task.cancel(true));
        executor.shutdownNow();
    }
}
