package com.personal.baton.watch.adapter.out.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedTaskExecutorTest {

    @Test
    void alreadyInterruptedCallerDoesNotStartWorkAndPreservesInterruption() throws Exception {
        AtomicBoolean workerCreated = new AtomicBoolean();
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            workerCreated.set(true);
            return Thread.ofPlatform().unstarted(task);
        });
        AtomicInteger cancelled = new AtomicInteger();
        try (var executor = new BoundedTaskExecutor(pool)) {
            try {
                Thread.currentThread().interrupt();
                assertThrows(CancellationException.class,
                        () -> executor.call(() -> "sent", Duration.ofSeconds(1), cancelled::incrementAndGet));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertFalse(workerCreated.get());
            assertEquals(1, cancelled.get());
            assertEquals("sent", executor.call(() -> "sent", Duration.ofSeconds(1), cancelled::incrementAndGet));
            assertEquals(1, cancelled.get());
        }
    }

    @Test
    void rejectsANonPositiveDeadlineWithoutSubmitting() {
        AtomicBoolean called = new AtomicBoolean();
        try (var executor = new BoundedTaskExecutor(1, 1, "test-task-")) {
            assertThrows(TimeoutException.class, () -> executor.call(() -> {
                called.set(true);
                return null;
            }, Duration.ZERO, () -> {}));
        }
        assertFalse(called.get());
    }

    @Test
    void cancelsRejectedWork() {
        AtomicInteger cancelled = new AtomicInteger();
        var executor = new BoundedTaskExecutor(1, 1, "test-task-");
        executor.close();

        assertThrows(CancellationException.class,
                () -> executor.call(() -> "sent", Duration.ofSeconds(1), cancelled::incrementAndGet));
        assertEquals(1, cancelled.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelsRunningWorkOnTimeoutOrCallerInterruption(boolean interruptCaller) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        try (var executor = new BoundedTaskExecutor(1, 1, "test-task-")) {
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    executor.call(() -> {
                        started.countDown();
                        try {
                            block.await();
                        } catch (InterruptedException exception) {
                            workerInterrupted.countDown();
                        }
                        return null;
                    }, interruptCaller ? Duration.ofSeconds(10) : Duration.ofMillis(200), cancelled::incrementAndGet);
                } catch (Exception exception) {
                    failure.set(exception);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(started.await(1, TimeUnit.SECONDS));
                if (interruptCaller) {
                    caller.interrupt();
                }
                caller.join(1_500);

                assertFalse(caller.isAlive());
                assertEquals(interruptCaller ? CancellationException.class : TimeoutException.class, failure.get().getClass());
                assertEquals(interruptCaller, interruptRestored.get());
                assertTrue(workerInterrupted.await(1, TimeUnit.SECONDS));
                assertEquals(1, cancelled.get());
            } finally {
                block.countDown();
                caller.interrupt();
                caller.join(1_000);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesCancelledQueueCapacityBeforeTheBusyWorkerFinishes(boolean interruptCaller) throws Exception {
        var queue = new ArrayBlockingQueue<Runnable>(1);
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicBoolean called = new AtomicBoolean();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        try (var executor = new BoundedTaskExecutor(pool)) {
            Thread caller = Thread.ofPlatform().unstarted(() -> {
                try {
                    executor.call(() -> {
                        called.set(true);
                        return null;
                    }, interruptCaller ? Duration.ofSeconds(10) : Duration.ofMillis(200), cancelled::incrementAndGet);
                } catch (Exception exception) {
                    failure.set(exception);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(occupied.await(1, TimeUnit.SECONDS));
                caller.start();
                Runnable queued = queue.poll(1, TimeUnit.SECONDS);
                assertNotNull(queued);
                queue.add(queued);
                if (interruptCaller) {
                    caller.interrupt();
                }
                caller.join(1_500);
                assertFalse(caller.isAlive());
                assertEquals(interruptCaller ? CancellationException.class : TimeoutException.class, failure.get().getClass());
                assertEquals(interruptCaller, interruptRestored.get());
                assertEquals(1, cancelled.get());
                assertFalse(called.get());
                // 기존 작업자가 계속 점유 중이어도 새 작업을 대기열에 넣을 수 있어야 한다.
                Future<String> next = pool.submit(() -> "accepted");
                release.countDown();
                assertEquals("accepted", next.get(1, TimeUnit.SECONDS));
            } finally {
                caller.interrupt();
                caller.join(1_000);
            }
        } finally {
            release.countDown();
        }
        assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void closeCancelsQueuedAndRunningWorkWithoutWaitingForTheirDeadline() throws Exception {
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var queue = new ArrayBlockingQueue<Runnable>(1);
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue);
        var executor = new BoundedTaskExecutor(pool);
        AtomicInteger cancelled = new AtomicInteger();
        AtomicBoolean queuedCalled = new AtomicBoolean();
        AtomicReference<Exception> runningFailure = new AtomicReference<>();
        AtomicReference<Exception> queuedFailure = new AtomicReference<>();
        Thread running = Thread.ofPlatform().start(() -> {
            try {
                executor.call(() -> {
                    runningStarted.countDown();
                    // 인터럽트에 반응하지 않는 차단 작업도 호출자는 기한을 기다리지 않아야 한다.
                    while (true) {
                        try {
                            if (release.await(10, TimeUnit.SECONDS)) {
                                return null;
                            }
                        } catch (InterruptedException ignored) {
                            // 계속 차단한다.
                        }
                    }
                }, Duration.ofSeconds(10), cancelled::incrementAndGet);
            } catch (Exception exception) {
                runningFailure.set(exception);
            }
        });
        Thread queued = Thread.ofPlatform().unstarted(() -> {
            try {
                executor.call(() -> {
                    queuedCalled.set(true);
                    return null;
                }, Duration.ofSeconds(10), cancelled::incrementAndGet);
            } catch (Exception exception) {
                queuedFailure.set(exception);
            }
        });
        try {
            assertTrue(runningStarted.await(1, TimeUnit.SECONDS));
            queued.start();
            Runnable waiting = queue.poll(1, TimeUnit.SECONDS);
            assertNotNull(waiting);
            queue.add(waiting);

            executor.close();
            running.join(1_000);
            queued.join(1_000);

            assertFalse(running.isAlive());
            assertFalse(queued.isAlive());
            assertInstanceOf(CancellationException.class, runningFailure.get());
            assertInstanceOf(CancellationException.class, queuedFailure.get());
            assertFalse(queuedCalled.get());
            assertEquals(2, cancelled.get());
            assertTrue(pool.isShutdown());
        } finally {
            release.countDown();
            running.join(1_000);
            queued.join(1_000);
        }
    }

    @Test
    void createsNamedDaemonThreads() throws Exception {
        try (var executor = new BoundedTaskExecutor(1, 1, "test-task-")) {
            Thread worker = executor.call(Thread::currentThread, Duration.ofSeconds(1), () -> {});

            assertTrue(worker.getName().startsWith("test-task-"));
            assertTrue(worker.isDaemon());
        }
    }
}
